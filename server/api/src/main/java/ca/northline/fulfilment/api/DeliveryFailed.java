package ca.northline.fulfilment.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.Externalized;

/**
 * {@code delivery.failed} (S-151) — the courier couldn't hand the order over: nobody answered ({@code absent}), the
 * door wasn't safe to approach ({@code unsafe}), the customer refused it ({@code refused}), or the courier couldn't get
 * in ({@code access}) — with a photo at the door. The courier brings the goods back to the shops (a {@code return}
 * stop each); the orders module moves the order to {@code delivery_failed} and asks the customer to choose re-delivery
 * or a refund. Kafka topic {@code fulfilment.delivery}, key = order id. Ids and codes only: never the courier's note.
 *
 * @param aggregateId the order
 * @param reason {@code absent} | {@code unsafe} | {@code refused} | {@code access} | {@code other}
 * @param attempt 1 for the first delivery, 2 for the first re-delivery, …
 */
@Externalized("fulfilment.delivery::#{aggregateId()}")
public record DeliveryFailed(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        @Nullable String runId,
        @Nullable String stopId,
        @Nullable String courierId,
        String reason,
        int attempt)
        implements DomainEvent {}
