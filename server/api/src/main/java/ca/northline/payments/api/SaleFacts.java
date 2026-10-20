package ca.northline.payments.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * S-152: what the loyalty module needs to know about a sale when its money is released ({@link EscrowReleased}) or
 * refunded ({@link RefundIssued}): who bought it, from whom, what kind of sale it was and the merchant's amount the
 * customer paid. Ids and amounts only.
 */
public interface SaleFacts {

    /**
     * @param amountCents the merchant's amount the customer paid (after a promo code and points, before tax)
     * @param refundedCents what refunds charged to the business have given back of it, or are about to (approved)
     * @param refundIds those refunds
     * @param listingId what was sold (offer, menu item or service), when known
     * @param heldAt when the money was held: the sale's time
     */
    record Fact(
            String escrowId,
            String merchantId,
            @Nullable String customerId,
            EscrowKind kind,
            String refType,
            String refId,
            long amountCents,
            long refundedCents,
            List<String> refundIds,
            @Nullable String listingId,
            @Nullable String orderNumber,
            String label,
            Instant heldAt) {}

    Optional<Fact> of(String escrowId);
}
