package ca.northline.booking.application;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: what the live ETA needs of a booking ({@code booking.bookings}). */
public interface VisitStore {

    /** @param siteLat / {@code siteLng} the job site, when the booking flow located it */
    record Visit(
            String id,
            String merchantId,
            @Nullable String memberUserId,
            @Nullable String customerId,
            String state,
            @Nullable Double siteLat,
            @Nullable Double siteLng) {}

    Optional<Visit> visit(String bookingId);
}
