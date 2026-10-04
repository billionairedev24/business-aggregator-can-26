package ca.northline.payments.application;

import ca.northline.payments.domain.Payout;
import java.util.Optional;

/**
 * The scheduled payout run for one business right now, whatever its schedule says about the day and the time — what
 * the nightly run would pay (everything payable, to the active bank account). LOCAL PROFILE ONLY: the end-to-end suite's
 * "payout run" step (S-117, {@code DevPayoutRunController}); deployed environments only pay on schedule.
 */
public interface RunPayouts {

    /** Empty when nothing is payable, payouts are paused, or there is no account to pay into. */
    Optional<Payout> runNow(String merchantId);
}
