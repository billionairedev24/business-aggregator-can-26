package ca.northline.payments.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code payout.sent} — money left the merchant's balance for their bank (scheduled or instant). The notification
 * worker emails the receipt. Kafka topic {@code payments.payout}, key = payout id.
 *
 * @param kind {@code scheduled} | {@code instant}
 */
@Externalized("payments.payout::#{aggregateId()}")
public record PayoutSent(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String merchantId,
        String kind,
        long amountCents,
        long feeCents,
        Instant arrivesAt)
        implements DomainEvent {}
