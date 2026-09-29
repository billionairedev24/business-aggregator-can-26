package ca.northline.merchants.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code merchant.submitted} — the owner submitted the application for review (status applicant → pending): trust
 * &amp; safety queue, registry re-checks, welcome call. Topic {@code merchants.merchant}, key = merchant id. Schema:
 * {@code resources/events/merchants.merchant_submitted.v1.schema.json}. No PII.
 *
 * @param merchantType provider | seller | kitchen | both
 */
@Externalized("merchants.merchant::#{aggregateId()}")
public record MerchantSubmitted(
        String eventId, Instant occurredAt, String aggregateId, String actorId, String merchantType)
        implements DomainEvent {}
