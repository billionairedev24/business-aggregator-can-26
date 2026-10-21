package ca.northline.catalogue.api;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * S-156: the business-level instant-book switch. Each service keeps its own instant-book setting; customers book a
 * service instantly only while the business has instant book on (its Studio switch) and Northline hasn't paused it
 * (S-93 "3 no-shows in 30 days → instant book off", or staff). Search and checkout read the effective value; a change
 * publishes {@link InstantBookChanged} so search re-indexes the business.
 */
public interface InstantBook {

    String PAUSED = "Northline paused instant book for your business. Standing shows why and how to appeal.";

    State state(String merchantId);

    /**
     * The business's own switch — Studio › Availability › "How bookings come in": instant book on or off. Turning it on
     * while Northline has paused it (and it was off) answers 409 {@code instant_book_paused}: the switch respects the
     * pause; keeping it on, or turning it off, is always possible.
     */
    State setEnabled(String merchantId, boolean enabled, String actorId);

    /** Northline pauses it ({@code cause}: {@code provider_no_shows} | {@code staff}); false when already paused. */
    boolean pause(String merchantId, String cause, String reason, String actorId);

    /** Ends Northline's pause when it was paused for {@code cause} (any cause when null); false otherwise. */
    boolean resume(String merchantId, @Nullable String cause, String actorId);

    /** Businesses paused for {@code cause}. */
    List<String> pausedFor(String cause);

    /**
     * @param enabled the business's own switch (on unless it turned it off)
     * @param pausedAt when Northline paused it, or null
     */
    record State(
            boolean enabled,
            @Nullable Instant pausedAt,
            @Nullable String pausedCause,
            @Nullable String pausedReason,
            @Nullable Instant updatedAt) {

        public static final State DEFAULT = new State(true, null, null, null, null);

        /** What customers get: on and not paused. */
        public boolean effective() {
            return enabled && pausedAt == null;
        }
    }
}
