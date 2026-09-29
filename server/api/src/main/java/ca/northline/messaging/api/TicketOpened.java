package ca.northline.messaging.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code ticket.opened} — a business opened a helpdesk case. The console support queue and the SLA monitor pick it
 * up. Externalized to Kafka topic {@code messaging.ticket}, key = ticket id. Schema:
 * {@code resources/events/messaging.ticket_opened.v1.schema.json}.
 */
@Externalized("messaging.ticket::#{aggregateId()}")
public record TicketOpened(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String merchantId,
        String topic,
        String priority,
        String channel,
        Instant slaDueAt)
        implements DomainEvent {}
