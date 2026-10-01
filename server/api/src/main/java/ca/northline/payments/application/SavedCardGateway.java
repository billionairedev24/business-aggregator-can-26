package ca.northline.payments.application;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: cards a customer saved at Stripe without paying (S-59, "Stripe Elements · SetupIntent (no charge)").
 * The card number never reaches Northline: Stripe.js confirms the SetupIntent in Stripe's iframe and Northline reads
 * back the PaymentMethod's brand, last four digits and expiry. Implemented by the Stripe adapter when a key is
 * configured, by the local fake otherwise.
 */
public interface SavedCardGateway {

    /** @param status Stripe's: {@code requires_payment_method | requires_action | processing | succeeded | canceled} */
    record SetupIntent(
            String id,
            String status,
            @Nullable String clientSecret,
            @Nullable String customer,
            @Nullable String paymentMethod) {

        public boolean succeeded() {
            return "succeeded".equals(status);
        }
    }

    /** @param brand Stripe's code ({@code visa}, {@code mastercard}, {@code amex} …) */
    record Card(String paymentMethod, String brand, String last4, int expMonth, int expYear, Instant created) {}

    /** A card-only, off-session SetupIntent for the customer ({@code seti_…}). */
    SetupIntent createSetupIntent(String stripeCustomer, String idempotencyKey);

    SetupIntent setupIntent(String setupIntentId);

    /** The customer's cards, newest first. */
    List<Card> cards(String stripeCustomer);

    /** {@code invoice_settings.default_payment_method}, or null. */
    @Nullable
    String defaultCard(String stripeCustomer);

    void makeDefault(String stripeCustomer, String paymentMethod, String idempotencyKey);

    /** Detaches the card from the customer (it can't be charged again). */
    void detach(String paymentMethod, String idempotencyKey);
}
