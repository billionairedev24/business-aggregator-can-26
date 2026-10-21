package ca.northline.hire.application;

import ca.northline.booking.api.CustomerJobRefs;
import ca.northline.shared.Conflict;
import ca.northline.shared.NotFound;
import ca.northline.trust.api.NoShowReports;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link ReportCustomerNoShow}: booking says whose job it is, trust takes the report. */
@Service
@RequiredArgsConstructor
@Transactional
class CustomerNoShowService implements ReportCustomerNoShow {

    static final Duration GRACE = Duration.ofMinutes(15);
    private static final Set<String> REPORTABLE = Set.of("confirmed", "en_route", "on_site");

    private final CustomerJobRefs jobs;
    private final NoShowReports reports;
    private final Clock clock;

    @Override
    public void report(String merchantId, String bookingId, String userId) {
        var visit = jobs.visit(merchantId, bookingId).orElseThrow(() -> new NotFound("job", bookingId));
        if (!REPORTABLE.contains(visit.state())) {
            throw new Conflict("not_reportable", NOT_REPORTABLE);
        }
        if (clock.instant().isBefore(visit.startsAt().plus(GRACE))) {
            throw new Conflict("too_early", TOO_EARLY);
        }
        if (!reports.customerNoShow(merchantId, bookingId, visit.customerId(), userId)) {
            throw new Conflict("already_reported", ALREADY);
        }
    }
}
