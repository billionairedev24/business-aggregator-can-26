package ca.northline.messaging.api;

import java.util.List;
import java.util.Locale;

/**
 * SPI for the help form's "Related to" list: a module offers the business's recent records a case can be about
 * (bookings, orders, payouts, disputes, documents). Messaging collects every bean implementing it; it contributes the
 * bookings, orders and disputes its own threads are linked to.
 */
public interface CaseReferences {

    /**
     * @param type {@code booking | order | payout | dispute | document}
     * @param id the record id
     * @param label display text in the caller's locale: "Booking BK-7712 · A. Osei", "Payout po_9Kx2"
     */
    record Reference(String type, String id, String label) {}

    List<Reference> recent(String merchantId, Locale locale);
}
