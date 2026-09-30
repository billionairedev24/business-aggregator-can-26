package ca.northline.payments.infra;

import ca.northline.payments.application.StripeEvent;
import ca.northline.payments.application.StripeEventVerifier;
import ca.northline.payments.application.StripeObject;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code Stripe-Signature} through stripe-java's {@link Webhook#constructEvent}: HMAC-SHA256 with the endpoint's
 * signing secret ({@code STRIPE_WEBHOOK_SECRET} for the platform endpoint, {@code STRIPE_CONNECT_WEBHOOK_SECRET} for
 * the Connect one — a delivery signed for one endpoint fails on the other) and a timestamp no older than the tolerance,
 * so a captured delivery can't be replayed later. During a secret roll Stripe signs with both secrets (several
 * {@code v1=} values); either matches.
 */
@Component
@RequiredArgsConstructor
class StripeSignatureVerifier implements StripeEventVerifier {

    private final PaymentsProperties properties;
    private final Clock clock;

    @Override
    public StripeEvent verify(StripeEvent.Endpoint endpoint, String payload, @Nullable String signature) {
        var secret = switch (endpoint) {
            case PLATFORM -> properties.stripeWebhookSecret();
            case CONNECT -> properties.stripeConnectWebhookSecret();
        };
        if (secret == null || secret.isBlank()) {
            throw new NotConfigured("No signing secret for the Stripe " + endpoint.code() + " webhook endpoint");
        }
        if (signature == null || signature.isBlank()) {
            throw new InvalidSignature("Stripe-Signature header is missing");
        }
        com.stripe.model.Event event;
        try {
            event = Webhook.constructEvent(
                    payload, signature, secret, properties.tolerance().toSeconds(), clock);
        } catch (SignatureVerificationException e) {
            throw new InvalidSignature(String.valueOf(e.getMessage()));
        } catch (RuntimeException e) {
            throw new InvalidSignature("Not a Stripe event: " + e.getMessage());
        }
        var raw = event.getDataObjectDeserializer().getRawJson();
        if (event.getId() == null || event.getType() == null || raw == null) {
            throw new InvalidSignature("Not a Stripe event");
        }
        return new StripeEvent(
                event.getId(),
                event.getType(),
                endpoint,
                event.getAccount(),
                Boolean.TRUE.equals(event.getLivemode()),
                Instant.ofEpochSecond(Objects.requireNonNullElse(
                        event.getCreated(), clock.instant().getEpochSecond())),
                StripeObject.parse(raw));
    }

    @Override
    public boolean livemode() {
        return properties.livemode();
    }
}
