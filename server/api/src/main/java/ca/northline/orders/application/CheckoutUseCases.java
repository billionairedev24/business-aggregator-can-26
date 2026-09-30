package ca.northline.orders.application;

import ca.northline.orders.application.CartUseCases.CartView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Checkout (S-51, design 06 {@code cart}): delivery window, address, substitutions, the tax quote (S-21
 * {@code TaxCalculations}), then one manual-capture PaymentIntent per order line (S-11 escrow model) plus one for the
 * delivery fee; placing the order once the card is authorized. Signed-in people only.
 */
public final class CheckoutUseCases {
    private CheckoutUseCases() {}

    public interface SetUpCheckout {
        /** @param mfa the sign-in used a second factor (then no step-up is asked) */
        Setup setup(String userId, boolean mfa, String market, String lang);
    }

    public interface QuoteCheckout {
        Quote quote(String userId, Request request, String lang);
    }

    public interface StartCheckout {
        /**
         * Takes the stock, prices the tax and opens the payments.
         *
         * @param mfa the sign-in used a second factor ({@code acr=mfa})
         * @param stepUpProof {@code X-Step-Up}, needed when {@code mfa} is false
         * @param clientKey the request's {@code Idempotency-Key}, passed on to Stripe
         */
        Started start(
                String userId,
                boolean mfa,
                @Nullable String stepUpProof,
                Request request,
                @Nullable String clientKey,
                String lang);
    }

    public interface PlaceOrder {
        /** Every payment of the checkout is authorized: the order is placed ({@code order.placed}). */
        Placed place(String userId, String checkoutId);
    }

    public interface ExpireCheckouts {
        /** Open checkouts past their expiry: stock back, authorizations canceled. Returns how many. */
        int expire(Instant now);
    }

    /**
     * @param kind {@code pooled} | {@code direct}
     * @param windowId the pooled run chosen
     * @param substitution {@code similar} | {@code refund} | {@code ask} (design: "Similar item · Refund it · Text me")
     */
    public record Request(
            String kind, @Nullable String windowId, AddressInput address, String substitution) {}

    /** A saved address ({@code addressId}) or a new one. */
    public record AddressInput(
            @Nullable String addressId,
            @Nullable String street,
            @Nullable String unit,
            @Nullable String city,
            @Nullable String province,
            @Nullable String postal,
            @Nullable String note) {}

    /**
     * @param stepUp {@code none} (signed in with a second factor), {@code required} (confirm with the passkey or
     *     authenticator the account has) or {@code enrol} (the account has none: add a passkey first)
     */
    public record Setup(
            CartView cart,
            List<AddressView> addresses,
            List<Option> options,
            Payment payment,
            String stepUp,
            String market,
            boolean served) {

        public Setup {
            addresses = List.copyOf(addresses);
            options = List.copyOf(options);
        }
    }

    public record AddressView(
            String id,
            String street,
            @Nullable String unit,
            String city,
            String province,
            String postal,
            @Nullable String note,
            boolean isDefault) {}

    /**
     * A delivery choice (design: "Tonight 6–9 pm · Pooled with 5 neighbours · $2.99", "Now · 45 min · Direct
     * courier · $9.99").
     *
     * @param id the window id, or {@code direct}
     * @param day {@code today} | {@code tomorrow} | {@code later} (Edmonton)
     */
    public record Option(
            String id,
            String kind,
            @Nullable String windowId,
            @Nullable String day,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt,
            @Nullable Instant orderBy,
            @Nullable Instant packBy,
            long feeCents,
            int households,
            @Nullable Integer etaMinutes) {}

    /** @param provider {@code stripe} | {@code fake} */
    public record Payment(String provider, @Nullable String publishableKey) {}

    public record TaxLine(String type, BigDecimal percent, long cents) {}

    public record Quote(
            long subtotalCents,
            long deliveryFeeCents,
            long taxCents,
            List<TaxLine> taxes,
            long totalCents,
            String market) {

        public Quote {
            taxes = List.copyOf(taxes);
        }
    }

    /** @param status {@code requires_action} (confirm with Stripe.js) or {@code authorized} */
    public record Intent(String paymentIntent, @Nullable String clientSecret, String status, long amountCents) {}

    public record Started(
            String checkoutId,
            String orderId,
            String ref,
            long totalCents,
            Instant expiresAt,
            Payment payment,
            List<Intent> intents) {

        public Started {
            intents = List.copyOf(intents);
        }
    }

    public record Placed(String orderId, String ref) {}
}
