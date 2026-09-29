package ca.northline.payments.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Earnings read model for the Studio dashboard ("Net earnings · 12 weeks" stacked by services vs parts, and the month
 * KPI). Net = released escrow minus Northline's fee, by the week / month it was released (America/Edmonton).
 */
public interface EarningsQuery {

    /** One week (Monday start). Food is reported separately for kitchens. */
    record WeekNet(LocalDate weekStart, long servicesCents, long partsCents, long foodCents) {
        public long totalCents() {
            return servicesCents + partsCents + foodCents;
        }
    }

    /**
     * Month to date vs the same number of days of the previous month.
     *
     * @param changePct rounded percentage change, null when the previous month had nothing
     */
    record MonthNet(
            long netCents, long previousCents, @Nullable Integer changePct) {}

    /** The last {@code weeks} weeks, oldest first, the current (partial) week last. */
    List<WeekNet> weeklyNet(String merchantId, int weeks);

    MonthNet monthNet(String merchantId);

    /**
     * What lands with the next payout: released balance plus escrow releasing before it ("$2,140.60 releasing
     * Friday").
     *
     * @param at the next scheduled payout, null when payouts are manual or paused
     */
    record Releasing(long amountCents, @Nullable Instant at) {}

    Releasing releasing(String merchantId);
}
