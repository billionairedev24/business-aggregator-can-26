package ca.northline.worker.events;

/**
 * What a consumer does with one event. Runs inside the transaction that records the event as processed for the
 * consumer: an exception rolls both back, and the record is retried ({@code .retry-<n>}, then {@code .dlq}).
 */
@FunctionalInterface
public interface EventHandler {
    void handle(EventEnvelope event);
}
