package ca.northline.payments.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Outbound port: {@code payments.stripe_events} — dedupe by Stripe's event id, then processing state. */
public interface StripeEventStore {

    enum State {
        RECEIVED,
        PROCESSED,
        IGNORED,
        FAILED
    }

    record Stored(StripeEvent event, State state, int attempts) {}

    /** Stores the event (payload redacted); false when this event id was already received. */
    boolean insert(StripeEvent event, Instant receivedAt);

    /** Locks the row for processing (waits for another processor of the same event). */
    Optional<Stored> lock(String eventId);

    void finish(String eventId, State state, Instant at, @org.jspecify.annotations.Nullable String note);

    void failed(String eventId, String error, Instant at);

    /** Received or failed events with fewer than {@code maxAttempts}, oldest first. */
    List<String> pending(int maxAttempts, int limit);

    /** Deletes processed and ignored events received before {@code before}; returns how many. */
    int purge(Instant before);
}
