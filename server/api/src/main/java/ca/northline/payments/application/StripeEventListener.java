package ca.northline.payments.application;

import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Processes each stored Stripe event after the receiving transaction commits (async, from the outbox). */
@Component
@RequiredArgsConstructor
class StripeEventListener {

    private final StripeEventProcessor processor;

    @ApplicationModuleListener
    void on(StripeEventReceived received) {
        processor.processOrRecordFailure(received.stripeEventId());
    }
}
