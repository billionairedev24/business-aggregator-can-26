package ca.northline.orders.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.Externalized;

/**
 * {@code order.placed} — a customer paid (the card is authorized) for an order; published once **per shop** on it,
 * with that shop's lines only, so each business's listeners and partner webhooks (S-33) see their own share. Kafka
 * topic {@code orders.order}, key = order id. Ids and amounts only (no names, no address).
 *
 * @param orderType {@code goods} | {@code food}
 * @param delivery {@code pooled} | {@code direct} | {@code pickup} (food: {@code direct} = the hot courier, S-57)
 * @param windowId the pooled run's window, when pooled
 * @param subtotalCents this shop's lines, before tax
 * @param taxCents GST/HST on this shop's lines
 */
@Externalized("orders.order::#{aggregateId()}")
public record OrderPlaced(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String merchantId,
        String customerId,
        String orderRef,
        String orderType,
        String delivery,
        @Nullable String windowId,
        long subtotalCents,
        long taxCents,
        List<Line> lines)
        implements DomainEvent {

    public OrderPlaced {
        lines = List.copyOf(lines);
    }

    /**
     * One order line of this shop.
     *
     * @param offerId what was sold: a catalogue offer (goods), or for food (S-57) the kitchen's menu item or combo id
     * @param itemKind {@code offer} (goods) · {@code menu_item} · {@code combo} (food, S-57)
     */
    public record Line(
            String lineId, String offerId, @Nullable String variantId, int qty, long amountCents, String itemKind) {

        /** A goods line (S-51). */
        public Line(String lineId, String offerId, @Nullable String variantId, int qty, long amountCents) {
            this(lineId, offerId, variantId, qty, amountCents, "offer");
        }
    }
}
