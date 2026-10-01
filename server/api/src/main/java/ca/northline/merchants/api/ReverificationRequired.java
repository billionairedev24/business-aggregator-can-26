package ca.northline.merchants.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code merchant.reverification_required} (S-82) — staff asked the business to verify one of its checks again (the check is expired now). Topic {@code merchants.merchant}, key = merchant id (search re-reads the
 * business). The reason is not in the payload (free text): {@code actionId} names the oversight action
 * ({@link SellerDirectory#action}).
 *
 * @param checkType {@code merchants.verifications.check_type}
 */
@Externalized("merchants.merchant::#{aggregateId()}")
public record ReverificationRequired(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String actorId,
        String actionId,
        String verificationId,
        String checkType)
        implements DomainEvent {}
