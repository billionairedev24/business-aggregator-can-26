package ca.northline.account.application;

import static java.util.Comparator.comparing;

import ca.northline.account.application.Businesses.Business;
import ca.northline.account.application.Favourites.FavouriteStore;
import ca.northline.account.domain.ActivityAction;
import ca.northline.account.domain.ActivityKind;
import ca.northline.account.domain.ActivityStatus;
import ca.northline.account.domain.PreferenceRules;
import ca.northline.account.domain.ProblemRules;
import ca.northline.booking.api.CustomerHistory;
import ca.northline.booking.api.CustomerHistory.BookingSummary;
import ca.northline.booking.api.CustomerHistory.RequestSummary;
import ca.northline.identity.api.AccountFacts;
import ca.northline.identity.api.PersonDirectory;
import ca.northline.identity.api.PlusMemberships;
import ca.northline.messaging.api.QuietHours;
import ca.northline.orders.api.CustomerOrders;
import ca.northline.orders.api.CustomerOrders.OrderSummary;
import ca.northline.payments.api.CustomerCaseQuery;
import ca.northline.payments.api.CustomerCaseQuery.CaseSummary;
import ca.northline.payments.api.SavedCards;
import ca.northline.region.api.Markets;
import ca.northline.trust.api.LoyaltyPoints;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The account area's activity reads (S-58), composed from orders, booking, payments, trust, identity and merchants
 * through their public APIs: "Orders &amp; bookings", "Your week", the wallet and the account menu's values.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class AccountActivityService implements ViewActivity, ViewUpcoming, ViewWallet, ViewAccountSummary {

    static final Duration WEEK = Duration.ofDays(7);
    static final int CASES = 200;

    private final CustomerOrders orders;
    private final CustomerHistory history;
    private final CustomerCaseQuery cases;
    private final LoyaltyPoints points;
    private final PlusMemberships plus;
    private final PersonDirectory people;
    private final FavouriteStore favourites;
    private final Businesses businesses;
    private final Markets markets;
    private final AccountFacts identity;
    private final Preferences.PreferencesStore preferences;
    private final SavedCards cards;
    private final QuietHours quietHours;
    private final Clock clock;

    // ── Orders & bookings ─────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public List<Item> items(String userId) {
        return activity(userId).items();
    }

    /** Everything at once, so the summary counts what the list shows. */
    private Activity activity(String userId) {
        var now = clock.instant();
        var orderList = orders.recent(userId, LIMIT);
        var bookingList = history.bookings(userId, LIMIT);
        var requests = history.openRequests(userId, LIMIT);
        var caseList = cases.cases(userId, CASES);
        var byRef = new HashMap<String, CaseSummary>();
        caseList.forEach(c -> byRef.putIfAbsent(c.refType() + ":" + c.refId(), c)); // newest first
        var merchantIds = new LinkedHashSet<String>();
        orderList.forEach(o -> merchantIds.addAll(o.merchantIds()));
        bookingList.forEach(b -> merchantIds.add(b.merchantId()));
        requests.forEach(r -> merchantIds.addAll(r.merchantIds()));
        var names = businesses.of(merchantIds);
        var members = people.people(bookingList.stream()
                .map(BookingSummary::memberUserId)
                .filter(Objects::nonNull)
                .toList());

        var items = new ArrayList<Item>();
        orderList.forEach(o -> items.add(order(o, names, byRef)));
        bookingList.forEach(b -> items.add(booking(b, names, members, byRef)));
        requests.forEach(r -> items.add(request(r, names, now)));
        var sorted = Stream.concat(
                        items.stream().filter(Item::active).sorted(comparing(Item::sortAt)),
                        items.stream()
                                .filter(i -> !i.active())
                                .sorted(comparing(Item::sortAt).reversed()))
                .limit(LIMIT)
                .toList();
        return new Activity(sorted, caseList);
    }

    private record Activity(List<Item> items, List<CaseSummary> cases) {}

    private Item order(OrderSummary o, Map<String, Business> names, Map<String, CaseSummary> byRef) {
        var food = "food".equals(o.type());
        var caseRef = food
                ? byRef.get("food_order:" + o.id())
                : o.lineIds().stream()
                        .map(l -> byRef.get("order_line:" + l))
                        .filter(Objects::nonNull)
                        .max(comparing(CaseSummary::openedAt))
                        .orElse(null);
        var open = caseRef != null && caseRef.open();
        var status = open
                ? ActivityStatus.CASE
                : food ? ActivityStatus.ofFood(o.state()) : ActivityStatus.ofOrder(o.state());
        var active = o.active();
        var when = active
                ? firstNonNull(o.windowStart(), o.etaAt(), o.placedAt())
                : firstNonNull(o.deliveredAt(), o.placedAt());
        var href = (food ? "/food/orders/" : "/orders/") + o.id();
        var reportable = caseRef == null && reportable(o);
        var action = open ? ActivityAction.VIEW_CASE
                : active ? ActivityAction.TRACK : reportable ? ActivityAction.REPORT : ActivityAction.DETAILS;
        if (action == ActivityAction.REPORT) {
            href = "/account/problem/" + (food ? "food/" : "order/") + o.id();
        }
        return new Item(
                o.id(),
                food ? ActivityKind.FOOD : ActivityKind.ORDER,
                o.ref(),
                "",
                nameList(o.merchantIds(), names),
                o.delivery(),
                o.merchantIds().size(),
                o.items(),
                when,
                active ? o.windowEnd() : null,
                o.totalCents(),
                status,
                active,
                caseRef(caseRef),
                action,
                open ? caseHref(caseRef) : href,
                when);
    }

    private Item booking(
            BookingSummary b,
            Map<String, Business> names,
            Map<String, PersonDirectory.Person> members,
            Map<String, CaseSummary> byRef) {
        var caseRef = byRef.get("booking:" + b.id());
        var open = caseRef != null && caseRef.open();
        var depositOnly = b.quoteId() != null && b.depositCents() > 0 && b.depositCents() < b.totalCents();
        var status = open ? ActivityStatus.CASE : ActivityStatus.ofBooking(b.state(), b.paid(), depositOnly);
        var business = names.get(b.merchantId());
        var member = b.memberUserId() == null ? null : members.get(b.memberUserId());
        var with = business == null
                ? List.<String>of()
                : List.of(member == null ? business.name() : business.name() + " · " + member.firstName());
        var slug = business == null ? null : business.slug();
        var active = b.active();
        ActivityAction action;
        String href;
        if (open) {
            action = ActivityAction.VIEW_CASE;
            href = caseHref(caseRef);
        } else if (caseRef == null && "completed".equals(b.state()) && b.paid()) {
            action = ActivityAction.REPORT;
            href = "/account/problem/booking/" + b.id();
        } else if (active || slug == null) {
            action = ActivityAction.DETAILS;
            href = slug == null ? null : "/providers/" + slug + "/book?step=done&booking=" + b.id();
        } else {
            action = ActivityAction.REBOOK;
            href = "/providers/" + slug;
        }
        return new Item(
                b.id(),
                ActivityKind.BOOKING,
                b.ref(),
                b.title(),
                with,
                null,
                1,
                1,
                b.startsAt(),
                null,
                b.totalCents(),
                status,
                active,
                caseRef(caseRef),
                action,
                href,
                b.startsAt());
    }

    private Item request(RequestSummary r, Map<String, Business> names, Instant now) {
        ActivityStatus status;
        if (r.declined()) {
            status = ActivityStatus.DECLINED;
        } else if (!r.openQuotes().isEmpty()) {
            status = ActivityStatus.QUOTE_READY;
        } else if (r.expiresAt() != null && r.expiresAt().isBefore(now)) {
            status = ActivityStatus.EXPIRED;
        } else {
            status = ActivityStatus.WAITING;
        }
        var active = status == ActivityStatus.QUOTE_READY || status == ActivityStatus.WAITING;
        var href = r.openQuotes().size() == 1
                ? "/quotes/" + r.openQuotes().getFirst().quoteId()
                : "/quotes/requests/" + r.id();
        var when = firstNonNull(r.preferredAt(), r.createdAt());
        return new Item(
                r.id(),
                ActivityKind.QUOTE,
                r.ref(),
                r.title(),
                nameList(r.quoted().isEmpty() ? r.merchantIds() : r.quoted(), names),
                null,
                r.merchantIds().size(),
                r.openQuotes().size(),
                when,
                null,
                r.lowestCents() == null ? 0 : r.lowestCents(),
                status,
                active,
                null,
                ActivityAction.VIEW_QUOTE,
                href,
                when);
    }

    /**
     * A delivered order still inside the window "Something's wrong" accepts as far as the list can tell (goods: 7 days
     * after delivery and not confirmed; food: 24 h after it). The problem page checks the escrow itself.
     */
    private boolean reportable(OrderSummary o) {
        var at = o.deliveredAt();
        if (at == null || !"delivered".equals(o.state())) {
            return false;
        }
        var window = "food".equals(o.type()) ? ProblemRules.FOOD_WINDOW : java.time.Duration.ofDays(7);
        return at.plus(window).isAfter(clock.instant());
    }

    private static List<String> nameList(Collection<String> merchantIds, Map<String, Business> names) {
        return merchantIds.stream()
                .map(names::get)
                .filter(Objects::nonNull)
                .map(Business::name)
                .distinct()
                .toList();
    }

    private static @Nullable CaseRef caseRef(@Nullable CaseSummary c) {
        return c == null ? null : new CaseRef(c.id(), c.number(), c.kind(), c.open());
    }

    static String caseHref(@Nullable CaseSummary c) {
        return c == null ? "/account?tab=help" : "/account?tab=help&case=" + c.id();
    }

    @SafeVarargs
    private static <T> T firstNonNull(@Nullable T... values) {
        for (var v : values) {
            if (v != null) {
                return v;
            }
        }
        throw new IllegalArgumentException("all null");
    }

    // ── Your week ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public List<Upcoming> upcoming(String userId, Locale locale) {
        var now = clock.instant();
        var copy = UpcomingCopy.of(locale, markets.zone(null));
        return activity(userId).items().stream()
                .filter(Item::active)
                .filter(i ->
                        i.status() == ActivityStatus.QUOTE_READY || i.when().isBefore(now.plus(WEEK)))
                .map(i -> new Upcoming(
                        i.kind().code() + ":" + i.id(),
                        copy.title(i),
                        copy.subtitle(i, now),
                        copy.state(i),
                        i.tone(),
                        Objects.requireNonNullElse(i.href(), "/account/orders")))
                .toList();
    }

    // ── Wallet & summary ──────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public Wallet wallet(String userId) {
        var p = points.of(userId);
        var membership = plus.of(userId)
                .map(m -> new ViewWallet.Plus(m.plan(), m.since(), m.renewsAt(), m.members()))
                .orElse(null);
        return new Wallet(new ViewWallet.Points(p.balance(), p.valueCents(), p.weekly()), membership);
    }

    @Override
    public Summary summary(String userId, Locale locale) {
        var activity = activity(userId);
        var p = points.of(userId);
        var person = people.people(List.of(userId)).get(userId);
        var facts = identity.of(userId);
        var prefs = preferences.find(userId).orElse(Preferences.Stored.DEFAULTS);
        var card = cards.defaultCard(userId).map(c -> new ViewAccountSummary.Card(brand(c.brand()), c.last4()));
        var quiet = quietHours
                .of(userId)
                .map(w -> new ViewAccountSummary.Quiet(hour(w.from(), locale), hour(w.to(), locale)));
        return new Summary(
                person == null ? null : person.reliabilityScore(),
                new ViewAccountSummary.Points(p.balance(), p.valueCents()),
                plus.of(userId).isPresent(),
                (int) activity.items().stream().filter(Item::active).count(),
                favourites.count(userId),
                (int) activity.cases().stream().filter(CaseSummary::open).count(),
                card.orElse(null),
                new ViewAccountSummary.Addresses(facts.addresses(), facts.householdMembers()),
                facts.mfaPrimary(),
                quiet.orElse(null),
                prefs.dietary().stream()
                        .map(d -> PreferenceRules.dietaryWord(d, locale))
                        .toList(),
                prefs.province() != null ? prefs.province() : facts.defaultProvince());
    }

    /** Stripe's brand code as the menu writes it: "visa" → "Visa", "amex" → "Amex". */
    static String brand(String code) {
        return switch (code) {
            case "mastercard" -> "Mastercard";
            case "amex" -> "Amex";
            case "diners" -> "Diners";
            case "jcb" -> "JCB";
            case "unionpay" -> "UnionPay";
            default -> code.isEmpty() ? code : Character.toUpperCase(code.charAt(0)) + code.substring(1);
        };
    }

    /** "10 pm" / "22 h", "7 am" / "7 h". */
    static String hour(java.time.LocalTime t, Locale locale) {
        if ("fr".equals(locale.getLanguage())) {
            return t.getMinute() == 0 ? t.getHour() + " h" : "%d h %02d".formatted(t.getHour(), t.getMinute());
        }
        var h = t.getHour() % 12 == 0 ? 12 : t.getHour() % 12;
        var suffix = t.getHour() < 12 ? "am" : "pm";
        return t.getMinute() == 0 ? h + " " + suffix : "%d:%02d %s".formatted(h, t.getMinute(), suffix);
    }
}
