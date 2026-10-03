package ca.northline.merchants.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code merchant.search_visibility_changed} (S-82) — the business was hidden from search or shown again, by staff or
 * by the rating floor rule (actor {@code system}). Topic {@code merchants.merchant}, key = merchant id (search re-reads
 * the business). The reason is not in the payload: {@code actionId} names the oversight action.
 *
 * @param hidden true when it is now hidden
 * @param cause {@code staff} | {@code rating_floor} | {@code pilot} (S-120: before and at a pilot market's launch)
 */
@Externalized("merchants.merchant::#{aggregateId()}")
public record MerchantSearchVisibilityChanged(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String actorId,
        String actionId,
        boolean hidden,
        String cause)
        implements DomainEvent {}
