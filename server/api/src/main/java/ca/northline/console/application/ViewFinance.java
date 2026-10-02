package ca.northline.console.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The console's finance screen (S-85, design 03 {@code finance}): escrow held, payouts in flight, the week's revenue
 * and its mix, take rate by tier, and the quarter's tax. Admin and finance. The Stripe ↔ ledger reconciliation is the
 * payments module's ({@code /api/v1/console/payments/reconciliation}).
 */
public interface ViewFinance {

    Finance finance();

    /**
     * @param plusCents Plus subscriptions: not recorded anywhere yet, always null
     * @param rewardsCents provider-funded rewards (pass-through): not recorded yet, always null
     */
    record Mix(
            long takeCents,
            long deliveryCents,
            long adjustmentsCents,
            @Nullable Long plusCents,
            @Nullable Long rewardsCents) {}

    /** @param gmvShare this tier's share of the week's money held, 0–1; null without any */
    record TierRow(
            String tier,
            long sellers,
            int rateBps,
            @Nullable Double gmvShare) {}

    /** @param nextFiling the GST/HST return of the quarter is due the last day of the month after it */
    record Tax(String period, long platformFeeCents, long facilitatorCents, LocalDate nextFiling) {}

    record Finance(
            Instant asOf,
            String timeZone,
            long escrowHeldCents,
            long escrowItems,
            long payoutsInFlightCents,
            long payoutsInFlightSellers,
            @Nullable Instant nextPayoutArrival,
            long revenueWeekCents,
            Mix mix,
            List<TierRow> tiers,
            Tax tax) {

        public Finance {
            tiers = List.copyOf(tiers);
        }
    }
}
