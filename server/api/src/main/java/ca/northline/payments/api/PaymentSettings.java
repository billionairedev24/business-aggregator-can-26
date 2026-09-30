package ca.northline.payments.api;

import org.jspecify.annotations.Nullable;

/**
 * How the browser collects a card (S-51): {@code stripe} — Stripe.js and the Payment Element with
 * {@code publishableKey}; {@code fake} — no Stripe configured (local), the consumer app shows a simulated form and the
 * fake gateway authorizes every PaymentIntent.
 */
public interface PaymentSettings {

    String provider();

    @Nullable
    String publishableKey();
}
