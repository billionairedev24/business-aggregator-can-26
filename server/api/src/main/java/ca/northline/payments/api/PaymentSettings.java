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

    /** S-157: the wallets Stripe may offer next to the card form; none without Stripe. */
    Wallets wallets();

    /**
     * Apple Pay / Google Pay (both are cards to Stripe: same manual-capture PaymentIntents, same step-up before them).
     * The browser or phone still decides whether it can show one (a wallet with a card, a registered domain).
     */
    record Wallets(boolean applePay, boolean googlePay) {
        public static final Wallets NONE = new Wallets(false, false);
    }
}
