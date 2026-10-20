package ca.northline.orders.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.Externalized;

/**
 * {@code return.updated} (S-151) — a customer's return moved on: {@code requested} (the shop is told), then
 * {@code approved} (with how it comes back: {@code pickup} on a run or {@code dropoff} at the shop), {@code declined},
 * {@code escalated} (to a Northline agent: the disputes queue), {@code collected} (the courier has it),
 * {@code received} (back at the shop), {@code refunded} or {@code withdrawn}. Topic {@code orders.return}, key = return
 * id. Ids, codes and the amount only — never the customer's or the shop's words.
 *
 * @param aggregateId the return ({@code orders.returns.id})
 * @param amountCents the returned lines' price before tax (what is refunded, with its tax)
 */
@Externalized("orders.return::#{aggregateId()}")
public record ReturnUpdated(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String returnRef,
        String orderId,
        String merchantId,
        String state,
        @Nullable String method,
        long amountCents)
        implements DomainEvent {}
