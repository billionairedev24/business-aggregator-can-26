package ca.northline.payments.application;

import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Receiving side of the Stripe webhooks: verify, store once (the event id is the primary key, so a retried or replayed
 * delivery is a no-op), queue for processing through the outbox, answer quickly. The work happens in
 * {@link StripeEventProcessor}.
 */
@Service
@RequiredArgsConstructor
class StripeWebhookService implements ReceiveStripeEvents {

    private final StripeEventVerifier verifier;
    private final StripeEventStore store;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    @Transactional
    public Outcome receive(StripeEvent.Endpoint endpoint, String payload, @Nullable String signature) {
        var event = verifier.verify(endpoint, payload, signature);
        if (!store.insert(event, clock.instant())) {
            return Outcome.DUPLICATE;
        }
        events.publishEvent(new StripeEventReceived(event.id()));
        return Outcome.ACCEPTED;
    }
}
