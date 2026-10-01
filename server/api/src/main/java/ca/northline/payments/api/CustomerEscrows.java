package ca.northline.payments.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The money a customer has in escrow for an order line, a food order or a booking (S-60): what "Something's wrong" may
 * still ask back, and until when. Read-only; the customer's own escrows only.
 */
public interface CustomerEscrows {

    /**
     * @param refType {@code order_line | food_order | booking}
     * @param state {@code held | released | refunded | disputed}
     * @param amountCents the merchant's amount, before tax
     * @param taxCents the GST/HST collected on it
     * @param fulfilledAt delivered / handed off / completed — the release clock's start; null before
     * @param releaseAt when the money goes to the business (the escrow window's end): goods 7 days after delivery,
     *     services 48 h after completion, food at handoff
     * @param openCase a refund case or dispute is already open on it
     */
    record EscrowFacts(
            String escrowId,
            String refType,
            String refId,
            String merchantId,
            String kind,
            String state,
            long amountCents,
            long taxCents,
            @Nullable Instant fulfilledAt,
            @Nullable Instant releaseAt,
            boolean openCase) {}

    List<EscrowFacts> of(String customerId, String refType, Collection<String> refIds);
}
