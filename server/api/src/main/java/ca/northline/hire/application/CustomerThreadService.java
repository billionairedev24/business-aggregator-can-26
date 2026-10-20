package ca.northline.hire.application;

import ca.northline.booking.api.CustomerJobRefs;
import ca.northline.identity.api.PersonDirectory;
import ca.northline.messaging.api.CustomerConversations;
import ca.northline.shared.NotFound;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link OpenCustomerThread} over {@link CustomerConversations}: the request's or job's customer, their short name, the
 * job's member as assignee (technicians see only their own threads; a quote request's thread has none — owners and
 * cooks see it).
 */
@Service
@RequiredArgsConstructor
class CustomerThreadService implements OpenCustomerThread {

    private final CustomerJobRefs jobs;
    private final CustomerConversations conversations;
    private final PersonDirectory people;

    @Override
    public String forQuoteRequest(String merchantId, String requestId) {
        return open(
                merchantId,
                jobs.quoteRequest(merchantId, requestId).orElseThrow(() -> new NotFound("quote request", requestId)));
    }

    @Override
    public String forJob(String merchantId, String bookingId) {
        return open(merchantId, jobs.booking(merchantId, bookingId).orElseThrow(() -> new NotFound("job", bookingId)));
    }

    private String open(String merchantId, CustomerJobRefs.JobRef ref) {
        var person = people.people(List.of(ref.customerId())).get(ref.customerId());
        return conversations.open(new CustomerConversations.Open(
                merchantId,
                ref.refType(),
                ref.refId(),
                ref.refCode(),
                ref.customerId(),
                person == null ? "Customer" : person.shortName(),
                ref.title(),
                ref.memberUserId()));
    }
}
