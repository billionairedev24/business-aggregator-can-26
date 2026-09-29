package ca.northline.messaging.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code message.sent} — a message was added to a thread. Notifications push it to the other side; trust opens an
 * {@code off_platform_payment} flag when {@code flagged} (contact details masked or payment outside Northline asked
 * for). Externalized to Kafka topic {@code messaging.message}, key = thread id. No message text (PII) in the payload.
 * Schema: {@code resources/events/messaging.message_sent.v1.schema.json}.
 */
@Externalized("messaging.message::#{aggregateId()}")
public record MessageSent(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String messageId,
        String merchantId,
        String threadKind,
        String senderRole,
        String senderId,
        boolean flagged)
        implements DomainEvent {}
