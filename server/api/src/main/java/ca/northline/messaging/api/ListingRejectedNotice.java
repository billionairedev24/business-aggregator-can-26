package ca.northline.messaging.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * S-92: a Northline reviewer rejected a listing or a dish in the console's vetting queue; the messaging module emails
 * the business's owners. Declared here and published by the catalogue and food modules, which may depend on
 * messaging; messaging listening to their own events would be a module cycle (catalogue → trust → messaging).
 * In-process only. The listing's name is the business's own content; no personal data.
 *
 * @param aggregateId the listing (offer / service) or menu item id
 * @param kind {@code product | service | dish}
 * @param reasons {@code prohibited | misleading | pricing | licence | images | other}
 * @param note the reviewer's words to the business, or null
 */
public record ListingRejectedNotice(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String merchantId,
        String kind,
        String listingName,
        List<String> reasons,
        @Nullable String note)
        implements DomainEvent {
    public ListingRejectedNotice {
        reasons = List.copyOf(reasons);
    }
}
