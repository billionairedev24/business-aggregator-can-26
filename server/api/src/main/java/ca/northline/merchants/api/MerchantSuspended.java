package ca.northline.merchants.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code merchant.suspended} (S-82) — staff suspended the business (status active or paused → suspended): its page, listings and checkout go offline at once. Topic {@code merchants.merchant}, key = merchant id (search re-reads the
 * business). The reason is not in the payload (free text): {@code actionId} names the oversight action
 * ({@link SellerDirectory#action}).
 *
 */
@Externalized("merchants.merchant::#{aggregateId()}")
public record MerchantSuspended(String eventId, Instant occurredAt, String aggregateId, String actorId, String actionId)
        implements DomainEvent {}
