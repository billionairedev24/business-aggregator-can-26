package ca.northline.merchants.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code merchant.renamed} — the customer-facing display name changed (search re-index, storefront CDN purge).
 * Externalized to Kafka topic {@code merchants.merchant}, key = merchant id. Schema:
 * {@code resources/events/merchants.merchant_renamed.v1.schema.json}. The display name is the public business name,
 * not personal data.
 */
@Externalized("merchants.merchant::#{aggregateId()}")
public record MerchantRenamed(
        String eventId, Instant occurredAt, String aggregateId, String actorId, String displayName)
        implements DomainEvent {}
