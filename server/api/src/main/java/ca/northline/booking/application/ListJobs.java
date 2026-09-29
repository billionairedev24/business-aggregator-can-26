package ca.northline.booking.application;

import ca.northline.booking.application.JobViews.JobSummary;
import ca.northline.shared.security.CurrentMember;
import java.time.Instant;
import java.util.List;

/** Jobs in a date range (week calendar, day and list views). Technicians only see their own jobs. */
public interface ListJobs {

    record Query(CurrentMember viewer, Instant from, Instant to) {}

    List<JobSummary> list(Query query);
}
