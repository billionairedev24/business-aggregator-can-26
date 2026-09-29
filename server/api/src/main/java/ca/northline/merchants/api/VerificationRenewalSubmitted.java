package ca.northline.merchants.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code verification.renewal_submitted} — an owner uploaded a new document for a licence, insurance, clearance letter
 * or permit (Stripe &amp; compliance › Upload); the console's review queue picks it up. Topic
 * {@code merchants.verification}, key = verification id. Schema
 * {@code events/merchants.verification_renewal_submitted.v1.schema.json}.
 */
@Externalized("merchants.verification::#{aggregateId()}")
public record VerificationRenewalSubmitted(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String actorId,
        String merchantId,
        String checkType,
        String documentId)
        implements DomainEvent {}
