package ca.northline.console.application;

import ca.northline.catalogue.api.ListingCategories;
import ca.northline.identity.api.SignupDates;
import ca.northline.merchants.api.CategorySource;
import ca.northline.merchants.api.StorefrontVisits;
import ca.northline.orders.api.ShopFunnel;
import ca.northline.region.api.LaunchStatus;
import ca.northline.region.api.Regions;
import ca.northline.region.api.WaitlistDemand;
import ca.northline.shared.CustomerActivity;
import ca.northline.shared.CustomerActivity.Purchase;
import ca.northline.shared.PlaceFilter;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ViewReports}: each owning module answers with counts or opaque ids (orders and booking:
 * {@link CustomerActivity}; storefront visits; carts and checkouts; listings' categories; signup dates; waitlists), and
 * this turns them into counts here, withholding small cells. Nothing is stored.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ReportsService implements ViewReports {

    static final int DAYS = 90;
    static final int WEEKS = 13;
    static final int COHORTS = 4;
    static final int TOP = 6;

    private final PlaceFilter places;
    private final List<CustomerActivity> activity;
    private final ShopFunnel shop;
    private final StorefrontVisits visits;
    private final ListingCategories listingCategories;
    private final CategorySource categories;
    private final SignupDates signups;
    private final WaitlistDemand waitlists;
    private final Regions regions;
    private final Clock clock;

    @Override
    public Report report(@Nullable String province) {
        var place = places.resolve(province, null);
        var scope = place.scope();
        ZoneId zone = place.zone();
        var now = clock.instant();
        var today = LocalDate.ofInstant(now, zone);
        var from = today.minusDays(DAYS - 1);
        var fromInstant = from.atStartOfDay(zone).toInstant();

        // weekly active customers: 13 weeks to this one, and the 13 before
        var thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var firstWeek = thisWeek.minusWeeks(WEEKS - 1);
        var previousStart = firstWeek.minusWeeks(WEEKS);
        // cohorts: the four months before this one, each followed for three months
        var thisMonth = YearMonth.from(today);
        var firstCohort = thisMonth.minusMonths(COHORTS);
        var cohortStart = firstCohort.atDay(1).atStartOfDay(zone).toInstant();

        var since = earliest(previousStart.atStartOfDay(zone).toInstant(), cohortStart);
        var purchases = new ArrayList<Purchase>();
        var sales = new HashMap<String, Long>();
        for (var source : activity) {
            purchases.addAll(source.purchases(scope, since, now));
            source.salesByListing(scope, fromInstant, now).forEach((k, v) -> sales.merge(k, v, Long::sum));
        }

        var weeks = weeks(purchases, firstWeek, zone);
        var counts = shop.counts(scope, fromInstant, now);
        var funnel = List.of(
                new Step("app_opens", null, false),
                new Step("browsed", safe(visits.total(scope, from, today.plusDays(1))), true),
                new Step("cart", safe(counts.carts()), counts.carts() != null),
                new Step("checkout", safe(counts.checkoutsStarted()), true),
                new Step("paid", safe(counts.checkoutsPaid()), true));
        return new Report(
                now,
                place.province(),
                from,
                weeks,
                funnel,
                cohorts(purchases, firstCohort, thisMonth, cohortStart, zone),
                top(sales),
                waitlist(place.province()));
    }

    private static List<Week> weeks(List<Purchase> purchases, LocalDate firstWeek, ZoneId zone) {
        var byWeek = new HashMap<LocalDate, Set<String>>();
        for (var p : purchases) {
            var week = LocalDate.ofInstant(p.at(), zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            byWeek.computeIfAbsent(week, _ -> new HashSet<>()).add(p.customerId());
        }
        var out = new ArrayList<Week>();
        for (var i = 0; i < WEEKS; i++) {
            var week = firstWeek.plusWeeks(i);
            out.add(new Week(
                    week, safe((long) byWeek.getOrDefault(week, Set.of()).size()), safe((long)
                            byWeek.getOrDefault(week.minusWeeks(WEEKS), Set.of())
                                    .size())));
        }
        return out;
    }

    private List<Cohort> cohorts(
            List<Purchase> purchases, YearMonth firstCohort, YearMonth thisMonth, Instant cohortStart, ZoneId zone) {
        var monthsBought = new HashMap<String, Set<YearMonth>>();
        for (var p : purchases) {
            if (!p.at().isBefore(cohortStart)) {
                monthsBought
                        .computeIfAbsent(p.customerId(), _ -> new HashSet<>())
                        .add(YearMonth.from(p.at().atZone(zone)));
            }
        }
        var signedUp = signups.signedUpSince(monthsBought.keySet(), cohortStart);
        var members = new HashMap<YearMonth, List<String>>();
        signedUp.forEach((id, at) -> members.computeIfAbsent(YearMonth.from(at.atZone(zone)), _ -> new ArrayList<>())
                .add(id));
        var out = new ArrayList<Cohort>();
        for (var i = 0; i < COHORTS; i++) {
            var month = firstCohort.plusMonths(i);
            var ids = members.getOrDefault(month, List.of());
            var n = ids.size();
            if (n > 0 && n < MIN_CELL) {
                out.add(new Cohort(month.toString(), null, null, null, null));
                continue;
            }
            out.add(new Cohort(
                    month.toString(),
                    (long) n,
                    rate(ids, monthsBought, month.plusMonths(1), thisMonth),
                    rate(ids, monthsBought, month.plusMonths(2), thisMonth),
                    rate(ids, monthsBought, month.plusMonths(3), thisMonth)));
        }
        return out;
    }

    /** Percent of {@code ids} who bought in {@code later}, one decimal; null before it starts or for no one. */
    private static @Nullable Double rate(
            List<String> ids, Map<String, Set<YearMonth>> monthsBought, YearMonth later, YearMonth thisMonth) {
        if (ids.isEmpty() || later.isAfter(thisMonth)) {
            return null;
        }
        var again = ids.stream()
                .filter(id -> monthsBought.getOrDefault(id, Set.of()).contains(later))
                .count();
        return Math.round(again * 1000.0 / ids.size()) / 10.0;
    }

    private List<CategorySales> top(Map<String, Long> salesByListing) {
        var byCategory = new HashMap<String, Long>();
        listingCategories
                .categories(salesByListing.keySet())
                .forEach((listing, category) ->
                        byCategory.merge(category, salesByListing.getOrDefault(listing, 0L), Long::sum));
        var top = byCategory.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(TOP)
                .toList();
        var names = new HashMap<String, Map<String, String>>();
        categories.byIds(top.stream().map(Map.Entry::getKey).toList()).forEach(c -> names.put(c.id(), c.names()));
        return top.stream()
                .map(e -> new CategorySales(e.getKey(), names.getOrDefault(e.getKey(), Map.of()), e.getValue()))
                .toList();
    }

    /** Provinces not live yet with people waiting, most first (only the chosen one when filtered). */
    private List<Waitlist> waitlist(@Nullable String province) {
        var notLive = new HashSet<String>();
        regions.provinces().stream()
                .filter(p -> p.status() != LaunchStatus.LIVE)
                .forEach(p -> notLive.add(p.code()));
        return waitlists.byProvince().entrySet().stream()
                .filter(e -> notLive.contains(e.getKey()) && (province == null || province.equals(e.getKey())))
                .sorted(Comparator.comparing(Map.Entry<String, Long>::getValue).reversed())
                .map(e -> new Waitlist(e.getKey(), safe(e.getValue())))
                .toList();
    }

    /** Small-cell suppression: 1 to {@link #MIN_CELL} − 1 → null. */
    static @Nullable Long safe(@Nullable Long n) {
        return n == null || (n > 0 && n < MIN_CELL) ? null : n;
    }

    private static Instant earliest(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
