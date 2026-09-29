package ca.northline.payments.application;

/**
 * Outbound port: Stripe Connect Express payments (platform account). Implemented by the Stripe adapter when real keys
 * are configured, by the local fake otherwise. Every call carries an idempotency key so a retried job can't pay twice.
 */
public interface PaymentGateway {

    /** Captures an authorized (manual-capture) PaymentIntent. */
    void capture(String stripePaymentIntent, long amountCents, String idempotencyKey);

    /** Transfers the merchant's net to their connected account; returns the transfer id ({@code tr_…}). */
    String transfer(String connectedAccount, long netCents, String escrowId, String idempotencyKey);

    /** Refunds to the customer's original payment method; returns the refund id ({@code re_…}). */
    String refund(String stripePaymentIntent, long amountCents, String idempotencyKey);
}
