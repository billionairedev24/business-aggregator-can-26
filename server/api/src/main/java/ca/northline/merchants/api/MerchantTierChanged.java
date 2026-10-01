package ca.northline.merchants.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code merchant.tier_changed} (S-82) — staff changed the business's tier by hand. Topic {@code merchants.merchant}, key = merchant id (search re-reads the
 * business). The reason is not in the payload (free text): {@code actionId} names the oversight action
 * ({@link SellerDirectory#action}).
 *
 * @param fromTier / {@code toTier}: registered | trusted | master
 */
@Externalized("merchants.merchant::#{aggregateId()}")
public record MerchantTierChanged(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String actorId,
        String actionId,
        String fromTier,
        String toTier)
        implements DomainEvent {}
