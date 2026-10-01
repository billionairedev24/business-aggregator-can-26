package ca.northline.console.application;

import ca.northline.shared.Backlog;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The console overview (S-91, design 03 {@code overview}): GMV, revenue and service levels of the last 7 days, GMV of
 * the last 12 weeks, system health, the work queue and what is live now — for the whole platform, a province or a
 * market of the region model. Composed from the owning modules' query APIs; nothing here reads another module's tables.
 */
public interface ViewOverview {

    /**
     * @param province two-letter code, or null for every province
     * @param market a region market id, or null for every market (of the province)
     */
    record Query(@Nullable String province, @Nullable String market) {}

    Overview view(Query query);

    /**
     * @param timeZone the zone dates are shown in: the market's, else the province's, else the platform's
     * @param from start of the 7 days the figures cover (now − 7 days); {@code previous*} figures are the 7 days before
     */
    record Overview(
            Instant asOf,
            String timeZone,
            Scope scope,
            Instant from,
            Headline headline,
            Kpis kpis,
            List<Week> weeks,
            List<Health> health,
            WorkQueue workQueue,
            Live live) {
        public Overview {
            weeks = List.copyOf(weeks);
            health = List.copyOf(health);
        }
    }

    /** The filter as resolved: province code and market (id + city) when filtered. */
    record Scope(
            @Nullable String province,
            @Nullable String market,
            @Nullable String city) {}

    /** "$212k GMV this week, 1,204 sellers, 7 verifications and 3 disputes waiting." */
    record Headline(long gmvCents, long sellers, long verifications, long disputes) {}

    /**
     * @param onTimeRatio delivered on time / delivered (pooled runs), null without deliveries
     * @param disputeRate disputes opened / orders + bookings, null without either
     * @param averageDeliveryFeeCents what customers paid per delivery, null without deliveries
     */
    record Kpis(
            long gmvCents,
            long previousGmvCents,
            long revenueCents,
            long orders,
            long bookings,
            @Nullable Double onTimeRatio,
            @Nullable Double disputeRate,
            @Nullable Long averageDeliveryFeeCents) {}

    /** One of the 12 weeks: goods (orders: shop and food) and services (bookings), oldest first. */
    record Week(Instant start, long goodsCents, long servicesCents) {}

    /** A system health tile; {@code key} as {@link HealthSignals.Signal} codes. */
    record Health(String key, @Nullable Double value, String status) {}

    /** Design "Work queue", in design order. */
    record WorkQueue(
            Backlog verifications,
            Backlog flaggedListings,
            Backlog disputes,
            Stuck stuckRuns,
            Flags trustFlags,
            long sellersBelowFloor) {}

    record Stuck(long count, @Nullable Instant oldestOverdue) {}

    record Flags(long count, @Nullable Instant oldest, boolean offPlatformPayment) {}

    /** Design "Live now". {@code pools}: the next open pooled run of each market in scope. */
    record Live(
            long couriersOnRuns, long couriersActive, long providersOnJobs, List<Pool> pools, long escrowHeldCents) {
        public Live {
            pools = List.copyOf(pools);
        }
    }

    /** "Orders in tonight's {city} pool · 312 · closes 5:19" — closes = the customers' cut-off. */
    record Pool(String market, String city, @Nullable String label, long orders, Instant closesAt, Instant startsAt) {}
}
