package ca.northline.console.application;

import ca.northline.merchants.api.SellerDirectory.Check;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The console's sellers directory and seller detail (S-82, design 03 {@code sellers} / {@code seller_detail}): every
 * business that applied, with tier, quality, 90-day GMV, dispute rate and what puts it at risk. Admin, trust &amp; safety
 * and support open it; the oversight actions are the merchants module's ({@code /api/v1/console/merchants/…}).
 */
public interface ViewSellers {

    Directory directory(Query query);

    Detail detail(String sellerId);

    /** @param q a business's display or legal name, or part of it */
    record Query(@Nullable String q, @Nullable String province, @Nullable String market) {}

    /**
     * What puts a business at risk (design: "Quality &lt; floor · insurance 21 d", "Off-platform payment mention",
     * "Licence pending TSBC").
     *
     * @param kind {@code quality_below} (value, floor) · {@code disputes_above} (value %, floor %) · {@code trust_flag}
     *     (rule) · {@code check_expiring} (checkType, registry, days) · {@code check_due} (checkType, registry, status)
     *     · {@code check_pending} (checkType, registry)
     */
    record Flag(
            String kind,
            @Nullable String rule,
            @Nullable String checkType,
            @Nullable String registry,
            @Nullable String status,
            @Nullable Double value,
            @Nullable Double floor,
            @Nullable Long days) {

        static Flag of(String kind) {
            return new Flag(kind, null, null, null, null, null, null, null);
        }
    }

    /** @param names the category's name per language ({@code en}, {@code fr} when translated) */
    record Category(String id, Map<String, String> names) {
        public Category {
            names = Map.copyOf(names);
        }
    }

    /**
     * @param disputeRate disputes opened ÷ orders and bookings in the last 90 days, null without any sale
     * @param quality the latest nightly quality score, null before the first
     */
    record Row(
            String id,
            String name,
            @Nullable Category category,
            String type,
            @Nullable String province,
            @Nullable String city,
            @Nullable String tier,
            @Nullable String status,
            @Nullable Integer quality,
            long gmv90Cents,
            @Nullable Double disputeRate,
            List<Flag> flags) {

        public Row {
            flags = List.copyOf(flags);
        }

        public boolean atRisk() {
            return !flags.isEmpty();
        }
    }

    /**
     * @param active approved businesses in scope
     * @param atRisk active ones with a flag
     */
    record Directory(Instant asOf, long active, long atRisk, List<Row> items, boolean truncated) {
        public Directory {
            items = List.copyOf(items);
        }
    }

    /** @param value / {@code floor}: the measure and the tier's floor (design 03 tier rules), null when not known */
    record Measure(@Nullable Double value, @Nullable Double floor) {}

    /** One quality component (QualityQuery): {@code on_time | photos | response | rebook | disputes}. */
    record Signal(String key, double value, int bar, int barFloor, boolean inverted) {}

    /** @param actorName the staff member's name; {@code actorRole} the console roles acted with */
    record TrailEntry(
            String id,
            String action,
            String reason,
            Map<String, String> detail,
            @Nullable String actorName,
            String actorRole,
            Instant at) {

        public TrailEntry {
            detail = Map.copyOf(detail);
        }
    }

    /**
     * @param stripeAccount the Connect account id shortened ({@code acct_1Kx…}), null without one
     * @param rating the review average and count
     */
    record Detail(
            Instant asOf,
            Row seller,
            Instant joinedAt,
            @Nullable Instant approvedAt,
            @Nullable String stripeAccount,
            double ratingAverage,
            int ratingCount,
            Measure quality,
            Measure onTime,
            Measure disputes,
            List<Signal> signals,
            List<Check> checks,
            List<TrailEntry> trail) {

        public Detail {
            signals = List.copyOf(signals);
            checks = List.copyOf(checks);
            trail = List.copyOf(trail);
        }
    }
}
