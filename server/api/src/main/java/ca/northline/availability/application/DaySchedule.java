package ca.northline.availability.application;

import ca.northline.availability.domain.SlotPlanner;
import ca.northline.availability.domain.TimeOff;
import ca.northline.availability.domain.TimeRange;
import ca.northline.booking.api.BookingCalendar;
import ca.northline.region.api.Regions;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * One member's day as slots see it: the hours in effect (or the special hours of a time-off entry), closures (time off,
 * statutory holidays of the business's province it doesn't open), and what already takes time — confirmed jobs and busy times from the
 * member's connected calendars (S-32). Shared by the Studio preview and the customer's booking calendar (S-55).
 */
@Component
@RequiredArgsConstructor
class DaySchedule {

    private final HoursRepository hours;
    private final TimeOffRepository timeOff;
    private final BookingCalendar calendar;
    private final CalendarSyncRepository calendarSync;
    private final Team team;
    private final Regions regions;

    /**
     * @param closed {@code time_off} or {@code holiday} when the day is closed, otherwise null
     * @param jobs confirmed jobs that day
     * @param busyBlocks busy times from the member's calendars that day
     */
    record Day(
            List<TimeRange> ranges,
            List<SlotPlanner.Busy> busy,
            int jobs,
            int busyBlocks,
            @Nullable String closed) {
        Day {
            ranges = List.copyOf(ranges);
            busy = List.copyOf(busy);
        }
    }

    /** @param override unsaved ranges of the Studio editor, or null for the saved hours */
    Day of(String merchantId, String memberUserId, LocalDate day, @Nullable List<TimeRange> override) {
        var closedBy = timeOff.from(merchantId, day).stream()
                .filter(t -> t.covers(day, memberUserId))
                .findFirst();
        var ranges = override != null
                ? override
                : hours.effectiveOn(merchantId, memberUserId, day)
                        .map(h -> h.on(day.getDayOfWeek()))
                        .orElse(List.of());
        var place = team.place(merchantId);
        var zone = place.zone();
        String closed = null;
        if (closedBy.isPresent()) {
            if (closedBy.get().kind() == TimeOff.Kind.CLOSED) {
                closed = "time_off";
                ranges = List.of();
            } else {
                ranges = closedBy.get().specialRanges();
            }
        } else if (regions.holiday(place.province(), day).isPresent()
                && !hours.openHolidays(merchantId).contains(day)) {
            closed = "holiday";
            ranges = List.of();
        }
        var from = start(day, zone);
        var to = start(day.plusDays(1), zone);
        var jobs = calendar.busy(merchantId, memberUserId, from, to);
        var calendarBusy = calendarSync.busy(merchantId, memberUserId, from, to);
        var busy = Stream.concat(
                        jobs.stream().map(b -> busy(b.startsAt(), b.endsAt(), day, zone)),
                        calendarBusy.stream().map(b -> busy(b.startsAt(), b.endsAt(), day, zone)))
                .toList();
        return new Day(ranges, busy, jobs.size(), calendarBusy.size(), closed);
    }

    static Instant start(LocalDate day, ZoneId zone) {
        return day.atStartOfDay(zone).toInstant();
    }

    static SlotPlanner.Busy busy(Instant from, Instant to, LocalDate day, ZoneId zone) {
        return new SlotPlanner.Busy(minutes(from, day, zone), minutes(to, day, zone));
    }

    /** Minutes since local midnight of {@code day}, clamped to the day. */
    static int minutes(Instant instant, LocalDate day, ZoneId zone) {
        var local = instant.atZone(zone);
        if (local.toLocalDate().isBefore(day)) {
            return 0;
        }
        if (local.toLocalDate().isAfter(day)) {
            return 24 * 60;
        }
        return local.getHour() * 60 + local.getMinute();
    }
}
