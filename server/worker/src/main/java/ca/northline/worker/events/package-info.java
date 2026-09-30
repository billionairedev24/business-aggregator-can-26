/**
 * The worker's consumer framework (S-26, CLAUDE.md § Events): envelope parsing against the versioned JSON Schemas,
 * dedupe on the event id ({@code events.processed_events}), poison messages straight to the DLQ, logging with event
 * ids, metrics, graceful shutdown and the DLQ replay tool. Consumers are {@code @RetryableTopic} + {@code @KafkaListener}
 * methods that hand each record to {@link ca.northline.worker.events.EventProcessing}. See
 * {@code docs/runbooks/events.md}.
 */
@NullMarked
package ca.northline.worker.events;

import org.jspecify.annotations.NullMarked;
