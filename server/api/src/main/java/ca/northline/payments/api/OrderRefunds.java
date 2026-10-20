package ca.northline.payments.api;

import java.time.Instant;
import java.util.Optional;

/**
 * S-151: money of an order that is cancelled, refunded after a failed delivery, or returned. Called by the orders
 * module from its retried listeners, so every method is idempotent. Unlike {@link CustomerCases}, nothing here waits
 * for a review: the shop, the customer within the rules, or Northline already decided.
 */
public interface OrderRefunds {

    /**
     * Everything held for the reference goes back to the customer now, through the refund queue's own steps: an
     * uncaptured card hold is voided (nothing was charged, nothing is posted), a captured charge is refunded to the card
     * with its GST/HST (reversed at Stripe Tax), and money already released to the merchant is taken back from their
     * transfer. A refund case {@code RF-…} records it for the customer's "Refunds &amp; cases" and the Studio.
     *
     * @param refType {@code order_line} (goods)
     * @param reason {@code order_cancelled} | {@code delivery_failed} | {@code returned}
     * @param what the line the customer sees on the case ("Order NL-50012 cancelled")
     * @return the refund's id; empty when nothing is held for the reference or it was already refunded
     */
    Optional<String> refundNow(String refType, String refId, String reason, String what);

    /** A return was requested: the reference's money waits (its release clock pauses) until the return is settled. */
    void hold(String refType, String refId);

    /** The return was declined or withdrawn: the money's normal release resumes. */
    void resume(String refType, String refId);

    /**
     * The order's delivery fee when the order closes without being delivered (cancelled, or refunded after a failed
     * delivery): {@code keepCents} of the authorization is captured (the failed-delivery fee, tax included; 0 by
     * default) and the rest released; a checkout tip waiting on the same authorization is canceled. Idempotent; a fee
     * already captured or canceled is left as it is.
     */
    void settleDeliveryFee(String orderId, long keepCents, Instant at);
}
