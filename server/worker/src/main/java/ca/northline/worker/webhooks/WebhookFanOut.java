package ca.northline.worker.webhooks;

import ca.northline.worker.events.EventEnvelope;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;

/**
 * The {@code webhooks} consumer's handler: maps the event to its public payload and queues one delivery per active
 * endpoint of the business subscribed to that type. Runs in the consumer framework's transaction (with the event's
 * dedupe claim), so a redelivered event queues nothing twice; sending is the {@link WebhookDispatcher}'s job.
 */
@Slf4j
public final class WebhookFanOut {

    private final WebhookPayloads payloads;
    private final WebhookStore store;
    private final Clock clock;

    public WebhookFanOut(WebhookPayloads payloads, WebhookStore store, Clock clock) {
        this.payloads = payloads;
        this.store = store;
        this.clock = clock;
    }

    public void on(EventEnvelope event) {
        var mapped = payloads.of(event).orElse(null);
        if (mapped == null) {
            return;
        }
        var now = clock.instant();
        var queued = 0;
        for (var endpointId : store.subscribers(mapped.merchantId(), mapped.type())) {
            if (store.queue(endpointId, mapped, now)) {
                queued++;
            }
        }
        log.debug("{} queued for {} endpoint(s)", mapped.type(), queued);
    }
}
