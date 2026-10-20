package ca.northline.payments.api;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * S-151: what couriers are owed and how it reaches them. fulfilment computes a finished run's pay under the market's
 * pay model and calls {@link #earned}; tips already accrue on the same ledger account ({@link CourierTips}). Couriers
 * are paid through Stripe Connect as recipients: an Express account with the {@code transfers} capability, onboarded
 * in Stripe's own pages; the weekly run transfers what is owed and pays it out to the bank Stripe collected.
 */
public interface CourierPay {

    String NOT_ONBOARDED = "Set up your payout account first.";

    /**
     * A courier earned {@code cents} for run {@code runId}: Northline's cost ({@code courier_pay}) owed to the courier
     * ({@code courier:<user id>}). Idempotent per run.
     */
    void earned(String runId, String courierUserId, long cents, Instant at);

    /** Tips allocated to the courier in [{@code from}, {@code to}), each with its order. */
    List<TipEarned> tips(String courierUserId, Instant from, Instant to);

    /** The courier's balance, payout account and latest payouts. */
    Status status(String courierUserId);

    /**
     * A link to Stripe's onboarding for the courier's payout account; the account is created the first time. The
     * courier comes back to {@code returnUrl} (done or not) or {@code refreshUrl} (the link expired).
     */
    String onboardingLink(String courierUserId, String returnUrl, String refreshUrl);

    record TipEarned(String tipId, String orderId, long cents, Instant at) {}

    /**
     * @param balanceCents owed and not paid out yet (pay and tips)
     * @param account null until the courier starts onboarding
     */
    record Status(long balanceCents, @Nullable Account account, List<Payout> payouts) {
        public Status {
            payouts = List.copyOf(payouts);
        }
    }

    /** @param bankLast4 the payout bank's last four digits, once Stripe has it */
    record Account(
            boolean payoutsEnabled,
            int requirementsDue,
            @Nullable String bankLast4) {}

    /** @param state {@code pending} | {@code in_transit} | {@code paid} | {@code failed} | {@code canceled} */
    record Payout(
            String id,
            long amountCents,
            String state,
            Instant createdAt,
            @Nullable Instant arrivesAt) {}
}
