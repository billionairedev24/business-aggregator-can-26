package ca.northline.booking.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One append-only row of {@code booking.booking_events}: a transition ({@code type} = the new state code), a
 * completion photo ({@code photo}) or a scope-change request ({@code approval_requested}).
 */
public record BookingLogEntry(
        String id,
        String bookingId,
        String type,
        Instant at,
        String actorId,
        @Nullable GeoPoint point,
        @Nullable String mediaId,
        @Nullable String note) {

    public static final String PHOTO = "photo";
    public static final String APPROVAL_REQUESTED = "approval_requested";
}
