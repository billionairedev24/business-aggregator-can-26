package ca.northline.payments.application;

import org.jspecify.annotations.Nullable;

/** Inbound port: a Stripe webhook delivery. */
public interface ReceiveStripeEvents {

    enum Outcome {
        /** New event, queued for processing. */
        ACCEPTED,
        /** Stripe sent this event id before (retry or replay); nothing happens again. */
        DUPLICATE
    }

    /** Verifies the signature, stores the event once and queues it; throws when the signature doesn't hold. */
    Outcome receive(StripeEvent.Endpoint endpoint, String payload, @Nullable String signature);
}
