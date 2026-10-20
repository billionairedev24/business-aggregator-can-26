package ca.northline.booking.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.Externalized;

/**
 * {@code booking.balance_failed} (S-153) — the card couldn't hold the balance of an accepted deposit quote. The
 * customer is asked to pay it from the booking page; the provider's team is told. {@code retryAt} = when Northline
 * tries again; null = that was the last attempt. Kafka topic {@code booking.booking}, key = booking id.
 *
 * @param reason {@code declined | authentication_required | no_saved_card}
 */
@Externalized("booking.booking::#{aggregateId()}")
public record BookingBalanceFailed(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String merchantId,
        String customerId,
        long amountCents,
        long taxCents,
        Instant startsAt,
        int attempt,
        String reason,
        @Nullable Instant retryAt)
        implements DomainEvent {}
