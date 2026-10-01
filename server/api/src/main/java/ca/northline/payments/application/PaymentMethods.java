package ca.northline.payments.application;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Payment methods and billing history of the signed-in consumer (S-59, design 06 payments): inbound ports, store. */
public final class PaymentMethods {
    private PaymentMethods() {}

    public static final String NOT_CONFIRMED = "The card isn't confirmed yet. Try again.";

    /** A saved card as the account shows it ("Visa ··4471 · Expires 09/28 · added May 2026"). */
    public record SavedCard(
            String id, String brand, String last4, int expMonth, int expYear, boolean isDefault, Instant addedAt) {}

    /** @param provider {@code stripe | fake} (PaymentSettings) */
    public record Setup(
            String setupIntentId,
            @Nullable String clientSecret,
            String provider,
            @Nullable String publishableKey) {}

    /**
     * One payment ("Sep 6 · Grocery run · NL-48190 · Visa ··4471 · $38.00").
     *
     * @param status {@code held | released | refunded | disputed | authorized | captured | canceled}
     */
    public record Payment(
            String id,
            Instant at,
            String what,
            @Nullable String ref,
            long amountCents,
            @Nullable String cardBrand,
            @Nullable String cardLast4,
            String status) {}

    public interface ManagePaymentMethods {
        List<SavedCard> cards(String userId);

        /** A SetupIntent for Stripe.js (or the fake) to confirm. */
        Setup startSetup(String userId);

        /** After Stripe.js confirmed it: the card is listed (and is the default when it's the first). */
        List<SavedCard> confirmSetup(String userId, String setupIntentId);

        List<SavedCard> makeDefault(String userId, String paymentMethod);

        List<SavedCard> remove(String userId, String paymentMethod);

        /** Newest first, at most {@code limit}. */
        List<Payment> billing(String userId, int limit);
    }

    /** Outbound port: the card summary kept for the account menu ({@code payments.customer_cards}) and billing rows. */
    public interface SavedCardStore {
        /** Replaces the person's cards with what Stripe reported (brand, last 4, expiry — never the number). */
        void replace(String customerId, List<SavedCard> cards);

        List<SavedCard> cards(String customerId);

        List<Payment> payments(String customerId, int limit);
    }
}
