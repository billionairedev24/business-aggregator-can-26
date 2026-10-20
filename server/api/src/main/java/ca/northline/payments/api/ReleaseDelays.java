package ca.northline.payments.api;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * S-156: escrow releases delayed for a missing completion photo ({@link EscrowReleaseDelayed}), for the standing
 * module: what a business has delayed, and lifting a delay (staff) — the release is due again on the normal clock.
 */
public interface ReleaseDelays {

    /** The business's delays, newest first. */
    List<Delay> of(String merchantId, int limit);

    /** Lifts every delay of the booking; false when none was active. */
    boolean lift(String bookingId, String staffId);

    record Delay(
            String bookingId,
            String escrowId,
            int hours,
            Instant delayedUntil,
            Instant createdAt,
            @Nullable Instant liftedAt,
            boolean released) {}
}
