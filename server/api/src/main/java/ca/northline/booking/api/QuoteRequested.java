package ca.northline.booking.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import java.util.List;
import org.springframework.modulith.events.Externalized;

/**
 * {@code quote.requested} (S-153) — a customer asked 1–3 providers for a quote. The notifications worker tells each
 * provider's team (Settings › Notifications row {@code quote_request}); the Studio column lists it. Ids, counts and
 * times only: never the description, the area or the customer's name. Kafka topic {@code booking.quote_request}, key =
 * request id. Schema: {@code booking.quote_requested.v1}.
 *
 * @param merchantIds the providers asked
 * @param categoryId the service category ({@code service.automotive.mobile-mechanic})
 * @param respondBy the response target of the Studio column (2 h)
 * @param photoCount how many photos came with it
 */
@Externalized("booking.quote_request::#{aggregateId()}")
public record QuoteRequested(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String quoteRef,
        List<String> merchantIds,
        String categoryId,
        Instant respondBy,
        Instant expiresAt,
        int photoCount)
        implements DomainEvent {
    public QuoteRequested {
        merchantIds = List.copyOf(merchantIds);
    }
}
