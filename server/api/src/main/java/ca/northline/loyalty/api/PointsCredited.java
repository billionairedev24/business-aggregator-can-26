package ca.northline.loyalty.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.Externalized;

/**
 * {@code points.credited} (S-152) — a completed sale or a referral credited points to a customer: the worker tells
 * them (Account › Notifications "Order updates"). Kafka topic {@code loyalty.points}, key = the reference. Ids and
 * numbers only. Schema {@code events/loyalty.points_credited.v1.schema.json}.
 *
 * @param aggregateId the escrow that earned ({@code reason = earn}) or the invitee ({@code reason = referral})
 * @param customerId who got the points
 * @param merchantId the business the sale was with; null for a referral
 * @param points what was credited in all (with Plus and a provider-funded reward)
 * @param rewardPoints of {@code points}, what the business's reward added
 * @param reason {@code earn} | {@code referral}
 */
@Externalized("loyalty.points::#{aggregateId()}")
public record PointsCredited(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String customerId,
        @Nullable String merchantId,
        long points,
        long rewardPoints,
        String reason)
        implements DomainEvent {}
