package ca.northline.booking.application;

import ca.northline.booking.application.JobViews.JobDetail;
import ca.northline.shared.security.CurrentMember;

/** The job card. Throws {@link ca.northline.shared.NotFound} for other businesses' jobs and, for technicians, others' jobs. */
public interface ViewJob {
    JobDetail view(CurrentMember viewer, String bookingId);
}
