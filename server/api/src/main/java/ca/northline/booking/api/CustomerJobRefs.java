package ca.northline.booking.api;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * S-153: a quote request or a job as one business may write to its customer about it — the customer, the reference
 * both sides see and the subject. Empty for a request not addressed to the business or someone else's job.
 */
public interface CustomerJobRefs {

    /**
     * @param refType {@code quote} (the request) or {@code booking}
     * @param memberUserId the member doing the job (a booking's), else null
     */
    record JobRef(
            String refType,
            String refId,
            String refCode,
            String customerId,
            String title,
            @Nullable String memberUserId) {}

    Optional<JobRef> quoteRequest(String merchantId, String requestId);

    Optional<JobRef> booking(String merchantId, String bookingId);

    /** S-156: the visit of one of the business's bookings (for a no-show report): its customer, state and start. */
    Optional<Visit> visit(String merchantId, String bookingId);

    /** @param state {@code booking.bookings.state} */
    record Visit(String bookingId, String customerId, String state, java.time.Instant startsAt) {}
}
