package ca.northline.payments.application;

import org.jspecify.annotations.Nullable;

/**
 * Outbound port: checks a webhook's {@code Stripe-Signature} (HMAC with the endpoint's signing secret, timestamp within
 * the tolerance — a replayed old delivery is refused) and parses the event.
 */
public interface StripeEventVerifier {

    /** The signature is missing, wrong, for another endpoint's secret, or too old. */
    final class InvalidSignature extends RuntimeException {
        public InvalidSignature(String message) {
            super(message);
        }
    }

    /** The endpoint has no signing secret configured. */
    final class NotConfigured extends RuntimeException {
        public NotConfigured(String message) {
            super(message);
        }
    }

    StripeEvent verify(StripeEvent.Endpoint endpoint, String payload, @Nullable String signature);

    /** Whether Northline runs with live keys: events of the other mode are ignored. */
    boolean livemode();
}
