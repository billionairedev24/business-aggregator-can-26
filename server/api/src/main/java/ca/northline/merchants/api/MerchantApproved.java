package ca.northline.merchants.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code merchant.approved} — trust &amp; safety approved the application (status pending → active): listings and the
 * storefront may go live, search indexes the merchant. Topic {@code merchants.merchant}, key = merchant id. Schema:
 * {@code resources/events/merchants.merchant_approved.v1.schema.json}.
 *
 * @param merchantType provider | seller | kitchen | both
 * @param tier registered | trusted | master
 */
@Externalized("merchants.merchant::#{aggregateId()}")
public record MerchantApproved(
        String eventId, Instant occurredAt, String aggregateId, String actorId, String merchantType, String tier)
        implements DomainEvent {}
