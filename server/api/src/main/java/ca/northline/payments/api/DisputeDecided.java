package ca.northline.payments.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code dispute.decided} — a dispute closed: {@code release} (the merchant keeps the money), {@code goodwill} (the
 * customer accepted the merchant's offer), {@code partial} or {@code full_refund}. Kafka topic {@code payments.dispute},
 * key = dispute id. Schema {@code events/payments.dispute_decided.v1.schema.json}.
 *
 * @param caseNumber {@code DS-…} as the Studio shows it (added by S-13 for the notification email; additive)
 * @param amountCents the disputed amount (added by S-13; additive)
 * @param decidedBy identity.users id of the agent, the merchant owner (full refund) or the customer (accepted offer);
 *     {@code stripe} when the card issuer decided a card dispute (S-12)
 */
@Externalized("payments.dispute::#{aggregateId()}")
public record DisputeDecided(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String merchantId,
        String caseNumber,
        long amountCents,
        String escrowId,
        String decision,
        long refundCents,
        String decidedBy)
        implements DomainEvent {}
