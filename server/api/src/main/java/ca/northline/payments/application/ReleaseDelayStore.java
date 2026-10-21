package ca.northline.payments.application;

import ca.northline.payments.api.ReleaseDelays.Delay;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Outbound port (S-156): {@code payments.release_delays}, one row per delayed escrow. */
public interface ReleaseDelayStore {

    /** False when the escrow already had a delay (the listener ran before). */
    boolean insert(String escrowId, String merchantId, String bookingId, int hours, Instant until, Instant at);

    /** The release may not happen before this while the delay is active. */
    Optional<Instant> activeUntil(String escrowId);

    List<Delay> of(String merchantId, int limit);

    /** Active delays of a booking's escrows. */
    List<Delay> active(String bookingId);

    void lifted(String escrowId, String staffId, Instant at);
}
