package ca.northline.booking.api;

import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The customer's day-of ETA for a service visit (mobile gaps part 2, design 01 C9): while the provider is on the way
 * and shares their position, how many minutes away they are. Only the distance and minutes leave the server, never
 * the provider's position.
 */
public interface VisitEtas {

    /**
     * @param state the booking's state
     * @param sharing the provider shares a live position right now (on the way, a position in the last 5 minutes)
     * @param minutesAway / {@code kmAway} from that position to the job site; null without a position or a located site
     * @param updatedAt when the position was taken
     * @param method {@code straight_line} (the estimate's method, for the screen's footnote)
     */
    record Eta(
            String state,
            boolean sharing,
            @Nullable Integer minutesAway,
            @Nullable Double kmAway,
            @Nullable Instant updatedAt,
            String method) {}

    /** The customer's own booking; empty when it isn't theirs. */
    Optional<Eta> eta(String customerId, String bookingId);
}
