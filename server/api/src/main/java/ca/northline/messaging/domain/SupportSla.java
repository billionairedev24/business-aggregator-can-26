package ca.northline.messaging.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * First-reply targets from the help screen ({@code helpSlaNote}), counted in support hours — 7 am to 11 pm Mountain
 * time, every day ("Support in English and French · 7 am–11 pm MT"):
 *
 * <ul>
 *   <li>urgent (safety, payment stuck, live order failing): a human within 15 min;
 *   <li>Master tier: priority queue, first reply within 1 h;
 *   <li>everyone else: first reply within 4 business hours.
 * </ul>
 */
public final class SupportSla {

    public static final ZoneId ZONE = ZoneId.of("America/Edmonton");
    static final LocalTime OPENS = LocalTime.of(7, 0);
    static final LocalTime CLOSES = LocalTime.of(23, 0);

    private SupportSla() {}

    public static TicketPriority priority(boolean urgent, boolean masterTier) {
        return urgent ? TicketPriority.URGENT : masterTier ? TicketPriority.PRIORITY : TicketPriority.NORMAL;
    }

    public static Duration target(TicketPriority priority) {
        return switch (priority) {
            case URGENT -> Duration.ofMinutes(15);
            case PRIORITY -> Duration.ofHours(1);
            case NORMAL -> Duration.ofHours(4);
        };
    }

    /** When Northline owes the next reply, counting only support hours from {@code from}. */
    public static Instant dueAt(Instant from, TicketPriority priority) {
        var remaining = target(priority);
        var at = from.atZone(ZONE);
        while (true) {
            var time = at.toLocalTime();
            if (time.isBefore(OPENS)) {
                at = at.with(OPENS);
            } else if (!time.isBefore(CLOSES)) {
                at = at.plusDays(1).with(OPENS);
            }
            var closes = at.with(CLOSES);
            var available = Duration.between(at, closes);
            if (remaining.compareTo(available) <= 0) {
                return at.plus(remaining).toInstant();
            }
            remaining = remaining.minus(available);
            at = closes;
        }
    }
}
