package ca.northline.console.application;

import ca.northline.booking.api.MarketplaceBookings;
import ca.northline.catalogue.api.VettingQueue;
import ca.northline.fulfilment.api.FleetStatus;
import ca.northline.merchants.api.MarketplaceMerchants;
import ca.northline.orders.api.DeliveryRuns;
import ca.northline.orders.api.MarketplaceOrders;
import ca.northline.payments.api.MarketplaceMoney;
import ca.northline.region.api.MarketProfile;
import ca.northline.region.api.Regions;
import ca.northline.shared.MerchantScope;
import ca.northline.shared.PlaceFilter;
import ca.northline.trust.api.TrustQueues;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class OverviewService implements ViewOverview {

    static final Duration WEEK = Duration.ofDays(7);
    static final int WEEKS = 12;
    /** A stop this late past its ETA makes its run "stuck" (design: "R-608 · 12 min"). */
    static final Duration STUCK_AFTER = Duration.ofMinutes(10);
    /** The Trusted tier's quality floor (design 03 tier rules: "quality ≥ 80"). */
    static final int QUALITY_FLOOR = 80;

    private final Clock clock;
    private final Regions regions;
    private final PlaceFilter places;
    private final MarketplaceMerchants merchants;
    private final MarketplaceOrders orders;
    private final MarketplaceBookings bookings;
    private final MarketplaceMoney money;
    private final FleetStatus fleet;
    private final VettingQueue vetting;
    private final TrustQueues trust;
    private final DeliveryRuns runs;
    private final HealthSignals health;

    @Override
    @Transactional(readOnly = true)
    public Overview view(Query query) {
        var now = clock.instant();
        var place = places.resolve(query.province(), query.market());
        var market = place.marketId() == null
                ? null
                : regions.marketById(place.marketId()).orElse(null);
        var province = place.province();
        var scope = place.scope();
        ZoneId zone = place.zone();
        var from = now.minus(WEEK);

        var goods = orders.gmvCents(scope, now.minus(WEEK.multipliedBy(WEEKS)), WEEK, WEEKS);
        var services = bookings.gmvCents(scope, now.minus(WEEK.multipliedBy(WEEKS)), WEEK, WEEKS);
        var weeks = new ArrayList<Week>();
        for (int i = 0; i < WEEKS; i++) {
            weeks.add(new Week(now.minus(WEEK.multipliedBy(WEEKS - i)), goods.get(i), services.get(i)));
        }
        var gmv = goods.getLast() + services.getLast();
        var previous = goods.get(WEEKS - 2) + services.get(WEEKS - 2);

        var orderCount = orders.placed(scope, from, now);
        var bookingCount = bookings.made(scope, from, now);
        var onTime = orders.onTime(scope, from, now);
        var opened = money.disputesOpened(scope, from, now);
        var kpis = new Kpis(
                gmv,
                previous,
                money.revenueCents(scope, from, now),
                orderCount,
                bookingCount,
                onTime.delivered() == 0 ? null : (double) onTime.onTime() / onTime.delivered(),
                orderCount + bookingCount == 0 ? null : (double) opened / (orderCount + bookingCount),
                orders.averageDeliveryFeeCents(scope, from, now));

        var fleetNow = fleet.now(now, STUCK_AFTER);
        var tiles = new ArrayList<Health>();
        health.read()
                .forEach(r -> tiles.add(
                        new Health(r.signal().code(), r.value(), r.status().code())));
        tiles.add(new Health(
                HealthSignals.Signal.COURIER_APP.code(),
                (double) fleetNow.offlineOnRun(),
                (fleetNow.offlineOnRun() == 0 ? HealthSignals.Status.OK : HealthSignals.Status.DEGRADED).code()));

        var verifications = merchants.applications(scope);
        var disputes = money.disputesForAgents(scope);
        var flags = trust.openFlags(scope);
        var queue = new WorkQueue(
                verifications,
                vetting.flagged(scope),
                disputes,
                new Stuck(fleetNow.stuckRuns(), fleetNow.oldestOverdue()),
                new Flags(flags.count(), flags.oldest(), flags.offPlatformPayment()),
                trust.belowFloor(scope, QUALITY_FLOOR));

        var live = new Live(
                fleetNow.onRuns(),
                fleetNow.active(),
                bookings.providersOnJobs(scope),
                pools(scope, province, market, now),
                money.escrowHeldCents(scope));

        return new Overview(
                now,
                zone.getId(),
                new Scope(province, market == null ? null : market.id(), market == null ? null : market.city()),
                from,
                new Headline(gmv, merchants.active(scope), verifications.count(), disputes.count()),
                kpis,
                weeks,
                tiles,
                queue,
                live);
    }

    /** The next open pooled run of each live market in scope (the filtered market, else the province's or all). */
    private List<Pool> pools(
            MerchantScope scope, @Nullable String province, @Nullable MarketProfile market, Instant now) {
        var markets = market != null
                ? List.of(market)
                : regions.markets().stream()
                        .filter(MarketProfile::live)
                        .filter(m -> province == null || m.province().equals(province))
                        .toList();
        var out = new ArrayList<Pool>();
        for (var m : markets) {
            runs.market(m.city())
                    .flatMap(name -> runs.upcoming(name, now).stream()
                            .filter(r -> r.openAt(now))
                            .findFirst())
                    .ifPresent(r -> out.add(new Pool(
                            m.id(),
                            m.city(),
                            r.label(),
                            orders.onRun(scope, r.windowId()),
                            r.orderBy(),
                            r.startsAt())));
        }
        return out;
    }
}
