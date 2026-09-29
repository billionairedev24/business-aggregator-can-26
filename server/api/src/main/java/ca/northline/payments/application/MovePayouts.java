package ca.northline.payments.application;

import ca.northline.payments.domain.Payout;
import ca.northline.payments.domain.PayoutSchedule;

/** Owner-only payout changes: instant payout (money-moving) and the schedule. */
public interface MovePayouts {

    record InstantCommand(String merchantId, long amountCents, String userId) {}

    /** "Instant payout · 1% fee" — arrives in ~30 min. */
    Payout instant(InstantCommand command);

    ViewPayouts.Overview changeSchedule(String merchantId, PayoutSchedule schedule, String userId);
}
