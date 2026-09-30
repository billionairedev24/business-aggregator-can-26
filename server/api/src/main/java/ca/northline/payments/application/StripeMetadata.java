package ca.northline.payments.application;

import ca.northline.payments.domain.Escrow;
import ca.northline.payments.domain.Refund;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Metadata Northline puts on Stripe objects: our ids only ({@code northline_*}), so a Stripe object leads back to the
 * escrow, merchant and job / order line — never names, e-mail addresses or anything else about the customer.
 */
final class StripeMetadata {

    private StripeMetadata() {}

    static Map<String, String> reference(String merchantId, String refType, String refId) {
        return Map.of("northline_merchant_id", merchantId, "northline_ref_type", refType, "northline_ref_id", refId);
    }

    static Map<String, String> escrow(Escrow escrow) {
        var metadata = new LinkedHashMap<>(reference(escrow.getMerchantId(), escrow.getRefType(), escrow.getRefId()));
        metadata.put("northline_escrow_id", escrow.getId());
        return Map.copyOf(metadata);
    }

    static Map<String, String> refund(Refund refund, @Nullable Escrow escrow) {
        var metadata = new LinkedHashMap<String, String>();
        if (escrow != null) {
            metadata.putAll(escrow(escrow));
        }
        metadata.put("northline_merchant_id", refund.getMerchantId());
        metadata.put("northline_refund_id", refund.getId());
        metadata.put("northline_case", refund.getCaseNumber());
        return Map.copyOf(metadata);
    }

    /** {@code booking:<id>} / {@code order_line:<id>} when the checkout gave no order-level group. */
    static String defaultTransferGroup(String refType, String refId) {
        return refType + ":" + refId;
    }
}
