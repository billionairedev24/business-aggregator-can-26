package ca.northline.booking.persistence;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Row of {@code booking.bookings} — only the columns the job flow changes. Bookings are created by the consumer
 * booking flow, so this row is only ever updated (optimistic lock on {@code version}, V040).
 */
@Table(schema = "booking", name = "bookings")
record BookingRow(
        @Id String id,
        String merchantId,
        @Nullable String memberUserId,
        @Nullable String customerId,
        String state,
        Instant updatedAt,
        @Version @Nullable Long version) {}
