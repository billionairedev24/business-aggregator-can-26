package ca.northline.payments.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.Externalized;

/**
 * {@code refund.issued} — a refund left the queue and was paid back to the customer's original payment method.
 * Kafka topic {@code payments.refund}, key = refund id. Schema {@code events/payments.refund_issued.v1.schema.json}.
 *
 * @param caseNumber {@code RF-…} as the Studio shows it (added by S-13 for the notification email; additive)
 * @param chargedTo {@code merchant} | {@code platform}
 */
@Externalized("payments.refund::#{aggregateId()}")
public record RefundIssued(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String merchantId,
        String caseNumber,
        @Nullable String escrowId,
        long amountCents,
        String chargedTo)
        implements DomainEvent {}
