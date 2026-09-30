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
     * @param refType {@code booking} | {@code order_line}
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
            long platformCents) {

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
            this(merchantId, refType, refId, customerId, amountCents, taxCents, transferGroup, clientKey, null, 0);
        }
    }

    /**
     * @param status {@code requires_action} (Stripe.js must confirm), {@code authorized}, …
     */
    record Started(String paymentIntent, @Nullable String clientSecret, String status) {}

    Started start(Request request);
}
