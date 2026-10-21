package ca.northline.booking.application;

import ca.northline.booking.api.CustomerJobRefs;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** {@link CustomerJobRefs} over the Studio's own read models of requests and jobs. */
@Service
@RequiredArgsConstructor
class CustomerJobRefService implements CustomerJobRefs {

    private final QuoteRequests requests;
    private final JobQueries jobs;

    @Override
    public Optional<JobRef> quoteRequest(String merchantId, String requestId) {
        return requests.find(merchantId, requestId)
                .filter(r -> r.customerId() != null)
                .map(r -> new JobRef(
                        "quote", r.id(), r.quoteRef(), Objects.requireNonNull(r.customerId()), r.title(), null));
    }

    @Override
    public Optional<Visit> visit(String merchantId, String bookingId) {
        return jobs.card(merchantId, bookingId)
                .map(JobQueries.JobCard::job)
                .filter(j -> j.customerId() != null)
                .map(j -> new Visit(
                        j.id(),
                        Objects.requireNonNull(j.customerId()),
                        j.state().code(),
                        j.startsAt()));
    }

    @Override
    public Optional<JobRef> booking(String merchantId, String bookingId) {
        return jobs.card(merchantId, bookingId)
                .map(JobQueries.JobCard::job)
                .filter(j -> j.customerId() != null)
                .map(j -> new JobRef(
                        "booking",
                        j.id(),
                        Objects.requireNonNullElse(j.ref(), j.id()),
                        Objects.requireNonNull(j.customerId()),
                        Objects.requireNonNullElse(j.title(), "Job"),
                        j.memberUserId()));
    }
}
