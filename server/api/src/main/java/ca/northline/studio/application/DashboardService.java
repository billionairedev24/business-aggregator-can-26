package ca.northline.studio.application;

import ca.northline.booking.api.BookingInsights;
import ca.northline.catalogue.api.CatalogueFacts;
import ca.northline.identity.api.PersonDirectory;
import ca.northline.identity.api.PersonDirectory.Person;
import ca.northline.merchants.api.ComplianceStatus;
import ca.northline.orders.api.OrderInsights;
import ca.northline.payments.api.EarningsSummary;
import ca.northline.studio.application.Dashboard.ComplianceItem;
import ca.northline.studio.application.Dashboard.Counts;
import ca.northline.studio.application.Dashboard.Earnings;
import ca.northline.studio.application.Dashboard.Item;
import ca.northline.studio.application.Dashboard.LowStock;
import ca.northline.studio.application.Dashboard.OpenCase;
import ca.northline.studio.application.Dashboard.Reputation;
import ca.northline.studio.application.Dashboard.RunOrder;
import ca.northline.studio.application.Dashboard.TodayJob;
import ca.northline.studio.application.Dashboard.Week;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Composes the dashboard from the public APIs of booking, orders, payments, trust, merchants, catalogue and identity.
 * No repository of another module is touched (Modulith-verified).
 */
@Service
@RequiredArgsConstructor
class DashboardService implements ViewDashboard {

    static final ZoneId ZONE = ZoneId.of("America/Edmonton");
    static final int EARNING_WEEKS = 12;
    static final int COACHING_JOBS = 20;
    /** Instant book / ordering pauses this long after a required document expired (docs/DECISIONS.md). */
    static final Duration COMPLIANCE_GRACE = Duration.ofDays(15);

    private final BookingInsights bookings;
    private final OrderInsights orders;
    private final EarningsSummary earnings;
    private final ca.northline.trust.api.Reputation reputation;
    private final ComplianceStatus compliance;
    private final CatalogueFacts catalogue;
    private final PersonDirectory people;
    private final Clock clock;

    @Override
    public Dashboard view(String merchantId, String viewerUserId, Locale locale) {
        var now = clock.instant();
        var today = LocalDate.now(clock.withZone(ZONE));
        var dayStart = start(today);
        var dayEnd = start(today.plusDays(1));
        var monthStart = start(today.withDayOfMonth(1));
        var nextMonth = start(today.withDayOfMonth(1).plusMonths(1));
        var lastMonth = start(today.withDayOfMonth(1).minusMonths(1));

        var jobs = bookings.jobs(merchantId, dayStart, dayEnd);
        var packing = orders.packing(merchantId, now);
        var run = orders.run(merchantId, now.minus(Duration.ofHours(12)), dayEnd.plus(Duration.ofDays(1)));
        var inbox = bookings.quoteInbox(merchantId, now);
        var volume = orders.volume(merchantId, monthStart, nextMonth);
        var cases = earnings.openCases(merchantId);

        var ids = new HashSet<String>();
        jobs.forEach(j -> {
            add(ids, j.customerId());
            add(ids, j.memberUserId());
        });
        run.forEach(o -> add(ids, o.customerId()));
        cases.forEach(c -> add(ids, c.customerId()));
        var names = people.people(ids);

        var counts = new Counts(
                jobs.size(),
                inbox.open(),
                inbox.earliestRespondBy(),
                packing.toPack(),
                packing.earliestCutoff(),
                packing.runLabel(),
                bookings.jobCount(merchantId, monthStart, nextMonth),
                volume.orders(),
                volume.items());

        var weekStarts = weekStarts(today);
        var release = earnings.nextRelease(merchantId, now);
        var money = new Earnings(
                earnings.netBetween(merchantId, monthStart, nextMonth),
                earnings.netBetween(merchantId, lastMonth, monthStart),
                release.map(EarningsSummary.Release::amountCents).orElse(null),
                release.map(EarningsSummary.Release::arrivesAt).orElse(null),
                earnings
                        .weeklyNet(
                                merchantId,
                                weekStarts.stream().map(DashboardService::start).toList())
                        .stream()
                        .map(w -> new Week(w.start().atZone(ZONE).toLocalDate(), w.servicesCents(), w.partsCents()))
                        .toList());

        var rating = reputation.rating(merchantId);
        var quality = reputation.latestQuality(merchantId);
        var rep = new Reputation(
                rating.map(ca.northline.trust.api.Reputation.Rating::average).orElse(null),
                rating.map(ca.northline.trust.api.Reputation.Rating::count).orElse(0L),
                quality.map(ca.northline.trust.api.Reputation.QualityScore::score)
                        .orElse(null),
                quality.map(ca.northline.trust.api.Reputation.QualityScore::components)
                        .orElse(Map.of()),
                earnings.refundRateBps(merchantId, start(today.minusDays(90)), dayEnd)
                        .orElse(null));

        var photos = bookings.photoCoverage(merchantId, COACHING_JOBS);
        return new Dashboard(
                today,
                jobs.stream()
                        .map(j -> new TodayJob(
                                j.id(),
                                j.startsAt(),
                                j.title(),
                                shortName(names, j.customerId()),
                                j.area(),
                                j.access(),
                                j.escrowHeldCents(),
                                j.state(),
                                firstName(names, j.memberUserId()),
                                j.memberUserId() == null || viewerUserId.equals(j.memberUserId())))
                        .toList(),
                run.stream()
                        .map(o -> new RunOrder(
                                o.orderId(),
                                o.ref(),
                                o.runLabel(),
                                shortName(names, o.customerId()),
                                o.area(),
                                o.items().stream()
                                        .map(i -> new Item(i.title(), i.qty()))
                                        .toList(),
                                o.packed()))
                        .toList(),
                counts,
                money,
                rep,
                cases.stream()
                        .map(c -> new OpenCase(
                                c.kind(),
                                shortName(names, c.customerId()),
                                "booking".equals(c.refType())
                                        ? bookings.jobTitle(c.refId()).orElse(c.reason())
                                        : c.reason()))
                        .toList(),
                catalogue.lowStock(merchantId, locale.getLanguage()).stream()
                        .map(s -> new LowStock(s.name(), s.stock()))
                        .toList(),
                compliance.dueItems(merchantId).stream()
                        .map(d -> new ComplianceItem(
                                d.checkType(),
                                d.registry(),
                                d.status(),
                                d.expiresAt(),
                                d.expiresAt() == null ? null : d.expiresAt().plus(COMPLIANCE_GRACE)))
                        .toList(),
                new Dashboard.Coaching(
                        photos.jobs(),
                        photos.withoutPhotos(),
                        today.withDayOfMonth(1).plusMonths(1)));
    }

    /** Mondays of the last 12 weeks, oldest first, ending with the current week. */
    static List<LocalDate> weekStarts(LocalDate today) {
        var monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return IntStream.range(0, EARNING_WEEKS)
                .mapToObj(i -> monday.minusWeeks(EARNING_WEEKS - 1L - i))
                .toList();
    }

    private static Instant start(LocalDate day) {
        return day.atStartOfDay(ZONE).toInstant();
    }

    private static void add(HashSet<String> ids, @Nullable String id) {
        if (id != null) {
            ids.add(id);
        }
    }

    private static @Nullable String shortName(Map<String, Person> names, @Nullable String id) {
        var p = id == null ? null : names.get(id);
        return p == null ? null : p.shortName();
    }

    private static @Nullable String firstName(Map<String, Person> names, @Nullable String id) {
        var p = id == null ? null : names.get(id);
        return p == null ? null : p.firstName();
    }
}
