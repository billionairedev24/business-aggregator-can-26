package ca.northline.payments.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.Externalized;

/**
 * {@code refund.case_updated} — a customer's refund case moved without the merchant acting: {@code requested} (the
 * merchant reviews it until {@code respondBy}), {@code approved} (review window lapsed on a small refund, or an agent
 * approved it), {@code agent_review} (window lapsed on a larger one), {@code denied} (an agent). Payment is
 * {@link RefundIssued}. The merchant's team is emailed (S-13). Kafka topic {@code payments.refund}, key = refund id.
 * Schema {@code events/payments.refund_case_updated.v1.schema.json}.
 */
@Externalized("payments.refund::#{aggregateId()}")
public record RefundCaseUpdated(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String merchantId,
        String caseNumber,
        String change,
        long amountCents,
        @Nullable Instant respondBy)
        implements DomainEvent {}
