package ca.northline.privacy.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code privacy.merchant_data_erased} — an erasure changed what a business shows the public (a review's author name and
 * words, a team member's name): the search projection re-reads the business. Externalized to the business's own topic
 * {@code merchants.merchant} (key = merchant id, so it is ordered with the business's other events, and the indexer
 * already reads it; a topic of its own would take Azure Event Hubs past its 100 hubs). Ids only: never whose data it
 * was. Schema:
 * {@code resources/events/privacy.merchant_data_erased.v1.schema.json}.
 *
 * @param aggregateId the business ({@code merchants.merchants.id})
 * @param requestId the privacy request ({@code privacy.requests.id})
 */
@Externalized("merchants.merchant::#{aggregateId()}")
public record MerchantDataErased(String eventId, Instant occurredAt, String aggregateId, String requestId)
        implements DomainEvent {}
