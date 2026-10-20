package ca.northline.payments.api;

import org.jspecify.annotations.Nullable;

/**
 * Checkout (consumer app, orders, booking): opens the manual-capture PaymentIntent that becomes the escrow hold of one
 * job or order line. Stripe.js confirms it with the customer's card ({@code clientSecret}); once Stripe reports it
 * authorized ({@code requires_capture}), the caller records the hold with {@link EscrowLifecycle#hold}, which checks
 * the authorization at Stripe. An order with several lines opens one PaymentIntent per line, all in the order's
 * transfer group, so each line is captured and released on its own clock.
 */
public interface PaymentAuthorizations {

    /**
     * @param refType {@code booking} | {@code order_line} | {@code order_delivery} (an order's delivery fee, merchant {@link #PLATFORM})
     * @param amountCents the merchant's amount before tax
     * @param taxCents GST/HST collected on top
     * @param transferGroup {@code order:<orderId>} or {@code booking:<bookingId>}
     * @param clientKey the customer's {@code Idempotency-Key}, when a request started this; null = the reference alone
     *     identifies the call
     * @param taxCalculationId the {@link TaxCalculations} quote the tax comes from ({@code amountCents} and
     *     {@code taxCents} must match it); null = the caller computed the tax, and the sale is reported to Stripe Tax
     *     with a calculation made at capture for the merchant's province
     * @param platformCents Northline's own charges authorized on the same card payment and captured with the escrow
     *     (S-57 food: courier fee, service fee, their tax, the courier's tip) — never transferred to the merchant; 0 for
     *     none. The matching {@link EscrowLifecycle.PlatformCharges} go with the hold.
     * @param creditCents what points pay (mobile gaps part 2): the card is authorized for that much less; at most the
     *     amount and its tax. The matching {@link EscrowLifecycle.Discount} goes with the hold.
     */
    record Request(
            String merchantId,
            String refType,
            String refId,
            String customerId,
            long amountCents,
            long taxCents,
            String transferGroup,
            @Nullable String clientKey,
            @Nullable String taxCalculationId,
            long platformCents,
            long creditCents) {

        /** Without points (the S-57 shape). */
        public Request(
                String merchantId,
                String refType,
                String refId,
                String customerId,
                long amountCents,
                long taxCents,
                String transferGroup,
                @Nullable String clientKey,
                @Nullable String taxCalculationId,
                long platformCents) {
            this(
                    merchantId,
                    refType,
                    refId,
                    customerId,
                    amountCents,
                    taxCents,
                    transferGroup,
                    clientKey,
                    taxCalculationId,
                    platformCents,
                    0);
        }

        /** Without platform charges (S-21 shape). */
        public Request(
                String merchantId,
                String refType,
                String refId,
                String customerId,
                long amountCents,
                long taxCents,
                String transferGroup,
                @Nullable String clientKey,
                @Nullable String taxCalculationId) {
            this(
                    merchantId,
                    refType,
                    refId,
                    customerId,
                    amountCents,
                    taxCents,
                    transferGroup,
                    clientKey,
                    taxCalculationId,
                    0,
                    0);
        }

        /** Without a tax calculation (the tax was worked out by the caller). */
        public Request(
                String merchantId,
                String refType,
                String refId,
                String customerId,
                long amountCents,
                long taxCents,
                String transferGroup,
                @Nullable String clientKey) {
            this(merchantId, refType, refId, customerId, amountCents, taxCents, transferGroup, clientKey, null, 0, 0);
        }
    }

    /**
     * @param status {@code requires_action} (Stripe.js must confirm), {@code authorized}, …
     */
    record Started(String paymentIntent, @Nullable String clientSecret, String status) {}

    default Started start(Request request) {
        return start(request, null);
    }

    /**
     * S-153: with one of the customer's saved cards ({@code pm_…} from Account › Payment methods): the PaymentIntent is
     * confirmed server-side with it — the customer is present (on-session), so 3-D Secure may still answer
     * {@code requires_action} and Stripe.js finishes it with the client secret. A card that isn't the customer's is a
     * 422 on {@code paymentMethodId}. Null = Stripe.js collects a card (the S-51 flow).
     */
    Started start(Request request, @Nullable String savedCard);

    /**
     * S-153: a hold the customer isn't there for — the balance of a deposit quote, 48 h before the event — on the card
     * behind an earlier authorization of theirs ({@code fromPaymentIntent}, the deposit's), off-session. Never throws for
     * a refusal: {@code status} is {@code authorized}, {@code requires_action} (the bank wants the customer; the attempt
     * is canceled) or {@code failed} (declined, or no saved card), with Stripe's decline code in {@code failure}.
     */
    OffSession startOffSession(Request request, String fromPaymentIntent, int attempt);

    /**
     * S-153: the latest PaymentIntent opened for a reference that a newer one did not replace and that still holds or
     * waits for the card (the balance the customer pays from the booking page), its {@code pi_…}.
     */
    java.util.Optional<String> latest(String refType, String refId);

    /** @param failure Stripe's decline code or our reason ({@code no_saved_card}), null when authorized */
    record OffSession(
            @Nullable String paymentIntent,
            String status,
            @Nullable String failure) {
        public boolean authorized() {
            return "authorized".equals(status);
        }
    }

    /**
     * The merchant id of a payment for Northline's own charges — the delivery fee of a goods order (S-51), which is no
     * merchant's escrow: its PaymentIntent is recorded under this id and never transferred.
     */
    String PLATFORM = "northline";

    /**
     * Whether Stripe holds the PaymentIntent for at least {@code amountCents} ({@code requires_capture}) — checkout
     * checks a payment that isn't an escrow hold (the delivery fee) this way before placing the order (S-51).
     */
    boolean authorized(String paymentIntent, long amountCents);

    /**
     * Cancels an authorization that won't become an order (checkout abandoned or failed, S-51): the hold on the
     * customer's card is released at once instead of lapsing after 7 days. Idempotent; a captured one is left alone.
     */
    void cancel(String paymentIntent);
}
