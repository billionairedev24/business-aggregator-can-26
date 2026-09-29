package ca.northline.payments.web;

import ca.northline.payments.domain.Payout;
import ca.northline.payments.domain.PayoutAccount;
import ca.northline.payments.domain.PayoutSchedule;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** JSON of the Payouts screen. */
final class PayoutResponses {
    private PayoutResponses() {}

    record Schedule(
            PayoutSchedule.Frequency frequency,
            @Nullable Integer weekday,
            PayoutSchedule.@Nullable MonthlyAnchor monthlyAnchor,
            PayoutSchedule.Reserve reserve) {}

    /** A bank account; {@code label} = "TD ··3391". Never the account number. */
    record Account(
            String id,
            PayoutAccount.Method method,
            String institutionName,
            String last4,
            String holderName,
            String label,
            PayoutAccount.State state,
            @Nullable Instant effectiveAt) {}

    /** Instant payout terms: 1 % (min $0.50), from $1.00. */
    record InstantTerms(boolean eligible, int feeBps, long minFeeCents, long minAmountCents) {}

    record Overview(
            long availableCents,
            long payableCents,
            long reserveCents,
            @Nullable Instant nextPayoutAt,
            Schedule schedule,
            @Nullable Account account,
            @Nullable Account pendingAccount,
            @Nullable Instant pausedUntil,
            InstantTerms instant) {}

    /** A payout; {@code reference} = Stripe's payout id ("po_9Kx2"). */
    record PayoutLine(
            String id,
            @Nullable String reference,
            Payout.Kind kind,
            Payout.State state,
            Instant createdAt,
            Instant arrivesAt,
            long amountCents,
            long feeCents,
            long netCents,
            int itemCount,
            @Nullable String destination) {}

    record Preview(@Nullable Instant nextPayoutAt, long amountCents) {}

    /** {@code mode}: {@code stripe} (Stripe.js Financial Connections with the client secret) or {@code fake}. */
    record LinkSession(
            String mode,
            @Nullable String clientSecret,
            @Nullable String publishableKey) {}
}
