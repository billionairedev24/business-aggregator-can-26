package ca.northline.worker.notifications;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port (S-102): whom an event's order, booking, quote request, refund case or courier run belongs to. Events
 * carry ids only, so the customer or courier is looked up when the notice is made. Adapter: {@link JdbcSubjects}, a
 * read-only view of the api's tables like {@link JdbcRecipients}.
 */
public interface Subjects {

    /** @param type {@code goods} | {@code food} (which order page the link opens) */
    record Order(String customerId, String type) {}

    record Booking(
            String customerId,
            @Nullable String merchantId,
            @Nullable Instant startsAt) {}

    Optional<Order> order(String orderId);

    Optional<Booking> booking(String bookingId);

    /** The customer who asked for quotes ({@code booking.quote_requests}). */
    Optional<String> quoteCustomer(String requestId);

    /** The customer of a refund case (its escrow's, else its booking's). */
    Optional<String> refundCustomer(String refundId);

    /** The {@code identity.users} id of a courier ({@code fulfilment.couriers}). */
    Optional<String> courierUser(String courierId);

    /** Confirmed bookings starting in {@code [from, to)}: the next day's, for the evening-before reminder. */
    List<String> confirmedBookingsStarting(Instant from, Instant to);
}
