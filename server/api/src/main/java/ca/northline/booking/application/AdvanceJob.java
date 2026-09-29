package ca.northline.booking.application;

import ca.northline.booking.application.JobViews.JobDetail;
import ca.northline.booking.domain.GeoPoint;
import ca.northline.shared.security.CurrentMember;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Today's job flow: Start travel → Check in on site → Complete with photos + report. Publishes {@code booking.en_route},
 * {@code booking.on_site} or {@code booking.completed}. Wrong state → 409 {@code job_state}.
 */
public interface AdvanceJob {

    enum Step {
        START_TRAVEL,
        CHECK_IN,
        COMPLETE
    }

    record Command(
            CurrentMember actor,
            String bookingId,
            Step step,
            @Nullable GeoPoint point,
            List<String> photoMediaIds,
            @Nullable String report) {
        public Command {
            photoMediaIds = List.copyOf(photoMediaIds);
        }
    }

    JobDetail advance(Command command);
}
