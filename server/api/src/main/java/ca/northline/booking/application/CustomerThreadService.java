package ca.northline.booking.application;

import ca.northline.identity.api.PersonDirectory;
import ca.northline.messaging.api.CustomerConversations;
import ca.northline.shared.NotFound;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link OpenCustomerThread} over {@link CustomerConversations}: the request's or job's customer, their short name, the
 * job's member as assignee (technicians see only their own threads; a quote request's thread has none — owners and cooks
 * see it).
 */
@Service
@RequiredArgsConstructor
class CustomerThreadService implements OpenCustomerThread {

    private final QuoteRequests requests;
    private final JobQueries jobs;
    private final CustomerConversations conversations;
    private final PersonDirectory people;

    @Override
    public String forQuoteRequest(String merchantId, String requestId) {
        var request = requests.find(merchantId, requestId)
                .filter(r -> r.customerId() != null)
                .orElseThrow(() -> new NotFound("quote request", requestId));
        var customer = Objects.requireNonNull(request.customerId());
        return conversations.open(new CustomerConversations.Open(
                merchantId,
                "quote",
                request.id(),
                request.quoteRef(),
                customer,
                name(customer),
                request.title(),
                null));
    }

    @Override
    public String forJob(String merchantId, String bookingId) {
        var job = jobs.card(merchantId, bookingId)
                .map(JobQueries.JobCard::job)
                .filter(j -> j.customerId() != null)
                .orElseThrow(() -> new NotFound("job", bookingId));
        var customer = Objects.requireNonNull(job.customerId());
        return conversations.open(new CustomerConversations.Open(
                merchantId,
                "booking",
                job.id(),
                Objects.requireNonNullElse(job.ref(), job.id()),
                customer,
                name(customer),
                job.title(),
                job.memberUserId()));
    }

    private String name(String customerId) {
        var person = people.people(List.of(customerId)).get(customerId);
        return person == null ? "Customer" : person.shortName();
    }
}
