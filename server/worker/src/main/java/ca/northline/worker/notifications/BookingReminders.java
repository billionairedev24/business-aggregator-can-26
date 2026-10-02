package ca.northline.worker.notifications;

import ca.northline.worker.events.ProcessedEvents;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.json.JsonMapper;

/**
 * The evening-before booking reminder (S-102; Account › Notifications "Evening-before reminder"): no domain event says
 * "tomorrow", so a job looks for confirmed bookings starting 2 to 36 hours from now and, once it is 18:00 or later the
 * day before in the customer's zone, sends the {@link PersonalNotices#REMINDER} notice. One reminder per booking and
 * start time (claimed as {@code booking-reminder}); a moved booking is reminded again. Quiet hours and the matrix apply
 * like any customer notice.
 */
@Slf4j
public final class BookingReminders {

    static final String CLAIMS = "booking-reminder";
    static final LocalTime EVENING = LocalTime.of(18, 0);
    static final Duration EARLIEST = Duration.ofHours(2);
    static final Duration LATEST = Duration.ofHours(36);

    private final Subjects subjects;
    private final Recipients recipients;
    private final PersonalNotices personal;
    private final Notifier notifier;
    private final ProcessedEvents claims;
    private final TransactionOperations separately;
    private final JsonMapper json;
    private final Clock clock;

    public BookingReminders(
            Subjects subjects,
            Recipients recipients,
            PersonalNotices personal,
            Notifier notifier,
            ProcessedEvents claims,
            TransactionOperations separately,
            JsonMapper json,
            Clock clock) {
        this.subjects = subjects;
        this.recipients = recipients;
        this.personal = personal;
        this.notifier = notifier;
        this.claims = claims;
        this.separately = separately;
        this.json = json;
        this.clock = clock;
    }

    /** Sends the reminders that are due; returns how many bookings were reminded. */
    public int run() {
        var now = clock.instant();
        var reminded = 0;
        for (var bookingId : subjects.confirmedBookingsStarting(now.plus(EARLIEST), now.plus(LATEST))) {
            var booking = subjects.booking(bookingId).orElse(null);
            var startsAt = booking == null ? null : booking.startsAt();
            var customer = booking == null
                    ? null
                    : recipients.customer(booking.customerId()).orElse(null);
            if (startsAt == null || customer == null) {
                continue;
            }
            var zone = customer.preferences().zone();
            var due = startsAt.atZone(zone)
                    .toLocalDate()
                    .minusDays(1)
                    .atTime(EVENING)
                    .atZone(zone)
                    .toInstant();
            if (now.isBefore(due)) {
                continue;
            }
            var key = "reminder:" + bookingId + ":" + startsAt.getEpochSecond();
            if (!Boolean.TRUE.equals(separately.execute(_ -> claims.claim(CLAIMS, key)))) {
                continue;
            }
            var payload = json.valueToTree(PersonalNotices.reminder(bookingId, now));
            try {
                personal.of(key, PersonalNotices.REMINDER, 1, payload).forEach(notifier::notify);
                reminded++;
            } catch (RuntimeException e) {
                separately.executeWithoutResult(_ -> claims.release(CLAIMS, key)); // the next run tries again
                log.warn("Reminder for booking {} not sent now: {}", bookingId, e.getMessage());
            }
        }
        return reminded;
    }
}
