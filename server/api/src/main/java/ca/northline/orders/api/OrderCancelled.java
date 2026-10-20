package ca.northline.orders.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import java.util.List;
import org.springframework.modulith.events.Externalized;

/**
 * {@code order.cancelled} (S-151) — a goods order, or one shop's part of it, was cancelled: by the customer before any
 * shop packed, by a shop for its own lines (with a reason, optionally restocked), by Northline staff, or because a
 * failed delivery was refunded (the customer's choice or the decision window passing). The cancelled lines' card holds
 * are voided (or their charges refunded), their stock restored where the canceller chose, and the delivery leaves its
 * run — the whole delivery, or one shop's pickup. Topic {@code orders.order}, key = order id. Ids and codes only: no
 * note, no customer.
 *
 * @param orderType {@code goods}
 * @param by {@code customer} | {@code merchant} | {@code staff} | {@code system}
 * @param reason the reason's code (AfterSale's lists, or {@code delivery_failed})
 * @param whole the whole order is closed (else only {@code merchantIds}' lines)
 * @param merchantIds the shops whose lines were cancelled
 * @param lineIds the cancelled lines ({@code orders.order_lines.id})
 * @param state the order's state now: {@code cancelled} | {@code refunded} (a failed delivery) | the state it keeps
 *     when only part of it was cancelled
 * @param cancellationId the {@code orders.cancellations} row
 */
@Externalized("orders.order::#{aggregateId()}")
public record OrderCancelled(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String orderType,
        String by,
        String reason,
        boolean whole,
        List<String> merchantIds,
        List<String> lineIds,
        String state,
        String cancellationId)
        implements DomainEvent {

    public OrderCancelled {
        merchantIds = List.copyOf(merchantIds);
        lineIds = List.copyOf(lineIds);
    }
}
