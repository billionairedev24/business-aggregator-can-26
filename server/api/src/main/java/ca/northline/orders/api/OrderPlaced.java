package ca.northline.orders.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.Externalized;

/**
 * {@code order.placed} — a customer's order was paid (its escrow hold exists) and handed to the merchants: a food order
 * to its kitchen (S-57), a goods order to its sellers (S-51 publishes the same event for {@code type = goods}). Topic
 * {@code orders.order}, key = order id. Ids only, no PII (no address, no names).
 *
 * @param orderType {@code food} | {@code goods}
 * @param merchantIds the merchants with lines on the order
 * @param fulfilmentMode {@code delivery} | {@code pickup} (food), null for goods
 * @param scheduledFor a scheduled food order's window start, else null
 */
@Externalized("orders.order::#{aggregateId()}")
public record OrderPlaced(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String orderType,
        String customerId,
        List<String> merchantIds,
        @Nullable String fulfilmentMode,
        @Nullable Instant scheduledFor,
        int lineCount,
        long totalCents)
        implements DomainEvent {

    public OrderPlaced {
        merchantIds = List.copyOf(merchantIds);
    }
}
