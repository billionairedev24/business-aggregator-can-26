package ca.northline.payments.application;

import ca.northline.payments.domain.Payout;
import ca.northline.payments.domain.PayoutSchedule;

/** Owner-only payout changes: instant payout (money-moving) and the schedule. */
public interface MovePayouts {

    /** @param idempotencyKey the client's {@code Idempotency-Key}; the Stripe payout's key is derived from it */
    record InstantCommand(String merchantId, long amountCents, String userId, String idempotencyKey) {}

    /** "Instant payout · 1% fee" — arrives in ~30 min. */
    Payout instant(InstantCommand command);

    ViewPayouts.Overview changeSchedule(String merchantId, PayoutSchedule schedule, String userId);
}
