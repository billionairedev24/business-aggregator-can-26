package ca.northline.booking.application;

import ca.northline.shared.security.CurrentMember;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Studio "On my way" with location sharing (mobile gaps part 2): while a job is en route, the member doing it shares
 * their position (the Studio in the phone's browser asks for the geolocation permission and the member's consent);
 * only the latest is kept, 5 minutes, in Valkey. Arriving, completing or switching sharing off removes it.
 */
public interface ShareTravel {

    String NOT_EN_ROUTE = "Start travel first — positions are shared only on the way.";
    String NOT_YOURS = "Only the team member doing this job can share their position.";

    /**
     * @param accepted false when it came sooner than the share interval after the last one (kept: the previous)
     * @param nextInSeconds when the next share is useful
     */
    record Shared(
            boolean accepted, int nextInSeconds, @Nullable Instant at) {}

    Shared share(CurrentMember actor, String bookingId, double lat, double lng);

    void stop(CurrentMember actor, String bookingId);
}
