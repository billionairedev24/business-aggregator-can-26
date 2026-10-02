package ca.northline.booking.application;

import ca.northline.booking.api.BookingConfirmed;
import ca.northline.booking.api.BookingSignedOff;
import ca.northline.booking.api.CustomerBookings;
import ca.northline.booking.domain.BookingLogEntry;
import ca.northline.booking.domain.BookingState;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.crypto.SecretSealer;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the bookings customers pay for (S-55) and quotes they accept (S-56). The member's time is checked again under
 * a per-member lock (the slot hold kept others away during checkout; this catches everything else — a Studio edit, an
 * expired hold); access instructions and the contact number are sealed apart from the booking. The customer follows the
 * job and signs it off from the consumer app (S-100).
 */
@Service
@RequiredArgsConstructor
@Transactional
class CustomerBookingService implements CustomerBookings {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The job-flow steps a customer sees (no GPS, approvals or photos: those stay with the business). */
    static final Set<String> STEPS = Set.of(
            BookingState.EN_ROUTE.code(),
            BookingState.ON_SITE.code(),
            BookingState.COMPLETED.code(),
            BookingState.SIGNED_OFF.code());

    private final CustomerBookingStore store;
    private final JobQueries jobs;
    private final BookingRepository bookings;
    private final SecretSealer sealer;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    public CustomerBooking book(NewBooking booking) {
        var existing = store.find(booking.bookingId());
        if (existing.isPresent()) {
            return existing.get();
        }
        if (store.lockAndCheckOverlap(booking.memberUserId(), booking.startsAt(), booking.endsAt())) {
            throw new Conflict("slot_taken", "That time was just taken. Pick another slot.");
        }
        var now = clock.instant();
        store.insert(booking, now);
        if (booking.accessNote() != null || booking.contactPhone() != null) {
            var note = new LinkedHashMap<String, String>();
            if (booking.accessNote() != null) {
                note.put("access", booking.accessNote());
            }
            if (booking.contactPhone() != null) {
                note.put("phone", booking.contactPhone());
            }
            store.sealAccess(
                    booking.bookingId(), sealer.seal(JSON.writeValueAsString(note), context(booking.bookingId())));
        }
        events.publishEvent(new BookingConfirmed(
                Ids.next(),
                now,
                booking.bookingId(),
                booking.merchantId(),
                booking.customerId(),
                booking.memberUserId(),
                booking.serviceId(),
                booking.quoteId(),
                booking.type(),
                booking.startsAt(),
                booking.endsAt(),
                booking.priceCents(),
                booking.depositCents()));
        return store.find(booking.bookingId()).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerBooking> find(String customerId, String bookingId) {
        return store.find(bookingId).filter(b -> customerIs(bookingId, customerId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Progress> progress(String customerId, String bookingId) {
        return find(customerId, bookingId).map(_ -> {
            var log = jobs.log(bookingId);
            var steps = log.stream()
                    .filter(l -> STEPS.contains(l.type()))
                    .map(l -> new Step(l.type(), l.at()))
                    .toList();
            var completed = log.stream()
                    .filter(l -> BookingState.COMPLETED.code().equals(l.type()))
                    .reduce((a, b) -> b);
            var photos = log.stream()
                    .filter(l -> l.mediaId() != null)
                    .filter(l ->
                            BookingState.COMPLETED.code().equals(l.type()) || BookingLogEntry.PHOTO.equals(l.type()))
                    .count();
            return new Progress(steps, completed.map(JobQueries.LogRow::note).orElse(null), (int) photos);
        });
    }

    @Override
    public CustomerBooking signOff(String customerId, String bookingId) {
        var own = find(customerId, bookingId).orElseThrow(() -> new NotFound("booking", bookingId));
        var booking = bookings.find(own.merchantId(), bookingId).orElseThrow(() -> new NotFound("booking", bookingId));
        if (booking.getState() == BookingState.SIGNED_OFF) {
            return own;
        }
        var now = clock.instant();
        var entry = booking.signOff(customerId, now);
        bookings.save(booking);
        bookings.append(List.of(entry));
        events.publishEvent(new BookingSignedOff(Ids.next(), now, bookingId, own.merchantId(), customerId));
        return store.find(bookingId).orElseThrow();
    }

    private boolean customerIs(String bookingId, String customerId) {
        return store.customerOf(bookingId).map(customerId::equals).orElse(false);
    }

    /** The AES-GCM additional data: a note sealed for one booking can't be opened for another. */
    static String context(String bookingId) {
        return "booking.access_notes:" + bookingId;
    }
}
