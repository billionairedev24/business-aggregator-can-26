package ca.northline.payments.application;

import ca.northline.payments.domain.Payout;
import ca.northline.payments.domain.PayoutAccount;
import ca.northline.payments.domain.PayoutSchedule;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Payouts screen: available balance, next payout, bank account, history, and the schedule preview. */
public interface ViewPayouts {

    /**
     * @param availableCents released balance minus money held for open refund cases ("Available now")
     * @param payableCents what a payout may take now: available minus the reserve
     * @param pausedUntil end of the 24 h hold after a bank account change
     * @param returned S-157: the latest payout, when the bank returned it to the account still in use — the Studio's
     *     notice ("update your bank details"); gone once the details change or a later payout goes out
     */
    record Overview(
            long availableCents,
            long payableCents,
            long reserveCents,
            @Nullable Instant nextPayoutAt,
            PayoutSchedule schedule,
            @Nullable PayoutAccount account,
            @Nullable PayoutAccount pendingAccount,
            @Nullable Instant pausedUntil,
            boolean instantEligible,
            @Nullable Returned returned) {}

    /** S-157: a payout the bank sent back, and Stripe's reason ({@code account_closed}, …; null = none given). */
    record Returned(String payoutId, long amountCents, @Nullable String failureCode, Instant sentAt) {}

    record Preview(@Nullable Instant nextPayoutAt, long amountCents) {}

    Overview overview(String merchantId);

    List<Payout> history(String merchantId, int limit);

    /** "Next payout would be Mon Sep 14 · $822.60 plus anything released before then." */
    Preview preview(String merchantId, PayoutSchedule schedule);
}
