package ca.northline.merchants.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code merchant.categories_changed} — staff moved a business's suggested category to a taxonomy category (S-94), so
 * search re-reads the business. Externalized to Kafka topic {@code merchants.merchant}, key = merchant id. Schema:
 * {@code resources/events/merchants.merchant_categories_changed.v1.schema.json}.
 */
@Externalized("merchants.merchant::#{aggregateId()}")
public record MerchantCategoriesChanged(
        String eventId, Instant occurredAt, String aggregateId, String actorId, String categoryId)
        implements DomainEvent {}
