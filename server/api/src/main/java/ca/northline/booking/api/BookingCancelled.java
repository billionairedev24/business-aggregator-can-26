package ca.northline.booking.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.Externalized;

/**
 * {@code booking.cancelled} (S-153) — the customer cancelled a booking before the work started. {@code late} = inside
 * the policy's window: {@code feeCents} (+ its tax) was taken from the hold, the rest released. The provider's team is
 * told and the slot is free again. Ids, times and amounts only. Kafka topic {@code booking.booking}, key = booking id.
 */
@Externalized("booking.booking::#{aggregateId()}")
public record BookingCancelled(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String merchantId,
        String customerId,
        @Nullable String memberUserId,
        Instant startsAt,
        boolean late,
        long feeCents,
        long feeTaxCents)
        implements DomainEvent {}
