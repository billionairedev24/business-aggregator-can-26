package ca.northline.console.application;

import ca.northline.booking.api.BookingMonitor;
import ca.northline.identity.api.PersonDirectory;
import ca.northline.merchants.api.CategorySource;
import ca.northline.merchants.api.SellerDirectory;
import ca.northline.merchants.api.SellerDirectory.Check;
import ca.northline.merchants.api.SellerDirectory.Seller;
import ca.northline.orders.api.OrderMonitor;
import ca.northline.payments.api.DisputeCounts;
import ca.northline.shared.NotFound;
import ca.northline.shared.PlaceFilter;
import ca.northline.trust.api.QualityQuery;
import ca.northline.trust.api.RatingQuery;
import ca.northline.trust.api.SellerStanding;
import ca.northline.trust.api.SellerStanding.Standing;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ViewSellers} from the merchants module's directory, trust's standing, quality and ratings, orders' and
 * booking's sales and payments' disputes — each read through its {@code api} (S-37).
 */
@Service
@RequiredArgsConstructor
class SellersService implements ViewSellers {

    static final Duration PERIOD = Duration.ofDays(90);
    static final int LIMIT = 2_000;

    /** Design 03 tier rules: Trusted "quality ≥ 80 · on-time ≥ 92% · disputes ≤ 1.5%", Master "≥ 85 · ≥ 95% · ≤ 1%". */
    record Floors(int quality, double onTimePct, double disputePct) {}

    static final Map<String, Floors> FLOORS =
            Map.of("trusted", new Floors(80, 92, 1.5), "master", new Floors(85, 95, 1.0));

    private static final Set<String> DUE = Set.of("todo", "expired", "rejected");
    private static final Set<String> PENDING_TYPES = Set.of("licence", "registry", "ahs_permit", "food_cert");

    private final Clock clock;
    private final PlaceFilter places;
    private final SellerDirectory directory;
    private final CategorySource categories;
    private final SellerStanding standing;
    private final OrderMonitor orders;
    private final BookingMonitor bookings;
    private final DisputeCounts disputes;
    private final QualityQuery quality;
    private final RatingQuery ratings;
    private final PersonDirectory people;

    @Override
    @Transactional(readOnly = true)
    public Directory directory(Query query) {
        var now = clock.instant();
        var place = places.resolve(query.province(), query.market());
        var sellers = directory.sellers(place.scope(), query.q(), LIMIT + 1);
        var shown = sellers.stream().limit(LIMIT).toList();
        var rows = rows(shown, now);
        var active = rows.stream().filter(r -> "active".equals(r.status())).toList();
        return new Directory(
                now, active.size(), active.stream().filter(Row::atRisk).count(), rows, sellers.size() > LIMIT);
    }

    @Override
    @Transactional(readOnly = true)
    public Detail detail(String sellerId) {
        var now = clock.instant();
        var file = directory.seller(sellerId).orElseThrow(() -> new NotFound("business", sellerId));
        var row = rows(List.of(file.seller()), now).getFirst();
        var score = quality.latest(sellerId);
        var floors = FLOORS.get(row.tier());
        var components = score.map(QualityQuery.QualityScore::components).orElse(List.of());
        Function<String, QualityQuery.@Nullable Component> component = key ->
                components.stream().filter(c -> c.key().equals(key)).findFirst().orElse(null);
        var onTime = component.apply("on_time");
        var disputeComponent = component.apply("disputes");
        var rating = ratings.summary(sellerId);
        var actors = people.people(file.trail().stream()
                .map(SellerDirectory.Oversight::actorId)
                .distinct()
                .toList());
        var account = file.stripeAccountId();
        return new Detail(
                now,
                row,
                file.seller().createdAt(),
                file.seller().approvedAt(),
                account == null ? null : account.substring(0, Math.min(account.length(), 7)) + "…",
                rating.average(),
                rating.count(),
                new Measure(
                        row.quality() == null ? null : row.quality().doubleValue(),
                        floors == null ? null : (double) floors.quality()),
                onTime != null
                        ? new Measure(onTime.value(), onTime.floor())
                        : new Measure(null, floors == null ? null : floors.onTimePct()),
                disputeComponent != null
                        ? new Measure(disputeComponent.value(), disputeComponent.floor())
                        : new Measure(
                                row.disputeRate() == null ? null : row.disputeRate() * 100,
                                floors == null ? null : floors.disputePct()),
                components.stream()
                        .map(c -> new Signal(c.key(), c.value(), c.bar(), c.barFloor(), c.inverted()))
                        .toList(),
                file.checks(),
                file.trail().stream()
                        .map(o -> new TrailEntry(
                                o.id(),
                                o.action(),
                                o.reason(),
                                o.detail(),
                                person(actors.get(o.actorId())),
                                o.actorRole(),
                                o.at()))
                        .toList());
    }

    private static @Nullable String person(PersonDirectory.@Nullable Person p) {
        return p == null ? null : p.displayName();
    }

    private List<Row> rows(List<Seller> sellers, Instant now) {
        if (sellers.isEmpty()) {
            return List.of();
        }
        var ids = sellers.stream().map(Seller::id).toList();
        var from = now.minus(PERIOD);
        var standings = standing.of(ids);
        var goods = orders.salesByMerchant(ids, from, now);
        var services = bookings.salesByMerchant(ids, from, now);
        var opened = disputes.opened(ids, from, now);
        var names = categories(sellers);
        return sellers.stream()
                .map(s -> {
                    var g = goods.get(s.id());
                    var b = services.get(s.id());
                    long gmv = (g == null ? 0 : g.gmvCents()) + (b == null ? 0 : b.gmvCents());
                    long sales = (g == null ? 0 : g.orders()) + (b == null ? 0 : b.bookings());
                    var rate = sales == 0 ? null : opened.getOrDefault(s.id(), 0L) / (double) sales;
                    var st = standings.getOrDefault(s.id(), new Standing(null, List.of()));
                    var category = s.categoryIds().isEmpty()
                            ? null
                            : names.get(s.categoryIds().getFirst());
                    return new Row(
                            s.id(),
                            s.name(),
                            category,
                            s.type(),
                            s.province(),
                            s.city(),
                            s.tier(),
                            s.status(),
                            st.quality(),
                            gmv,
                            rate,
                            flags(s, st, rate, now),
                            s.searchHidden());
                })
                .toList();
    }

    private Map<String, Category> categories(Collection<Seller> sellers) {
        var ids = sellers.stream()
                .flatMap(s -> s.categoryIds().stream().limit(1))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return categories.byIds(ids).stream()
                .collect(Collectors.toMap(
                        CategorySource.Category::id, c -> new Category(c.id(), c.names()), (a, _) -> a));
    }

    static List<Flag> flags(Seller s, Standing st, @Nullable Double disputeRate, Instant now) {
        var out = new ArrayList<Flag>();
        var floors = FLOORS.get(s.tier());
        if (floors != null && st.quality() != null && st.quality() < floors.quality()) {
            out.add(new Flag(
                    "quality_below",
                    null,
                    null,
                    null,
                    null,
                    st.quality().doubleValue(),
                    (double) floors.quality(),
                    null));
        }
        if (floors != null && disputeRate != null && disputeRate * 100 > floors.disputePct()) {
            out.add(new Flag("disputes_above", null, null, null, null, disputeRate * 100, floors.disputePct(), null));
        }
        st.openFlags().forEach(rule -> out.add(new Flag("trust_flag", rule, null, null, null, null, null, null)));
        for (Check c : s.attention()) {
            if (DUE.contains(c.status())) {
                out.add(new Flag("check_due", null, c.checkType(), c.registry(), c.status(), null, null, null));
            } else if ("submitted".equals(c.status()) && PENDING_TYPES.contains(c.checkType())) {
                out.add(new Flag("check_pending", null, c.checkType(), c.registry(), c.status(), null, null, null));
            } else if ("verified".equals(c.status()) && c.expiresAt() != null) {
                var days = Math.max(0, Duration.between(now, c.expiresAt()).toDays());
                out.add(new Flag("check_expiring", null, c.checkType(), c.registry(), c.status(), null, null, days));
            }
        }
        return out;
    }
}
