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
     */
    record Request(
            String merchantId,
            String refType,
            String refId,
            String customerId,
            long amountCents,
            long taxCents,
            String transferGroup,
            @Nullable String clientKey) {}

    /**
     * @param status {@code requires_action} (Stripe.js must confirm), {@code authorized}, …
     */
    record Started(String paymentIntent, @Nullable String clientSecret, String status) {}

    Started start(Request request);
}
