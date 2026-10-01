package ca.northline.search.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * "Now" as the index stores time, in the market's time zone ({@link SearchSettings#zone}): minute of the local week
 * (Monday 00:00 = 0) for weekly hours, minute of the local day for the same-day cut-off, the local date for "sold out
 * today", and the instant for kitchen pauses.
 */
public record SearchMoment(Instant instant, int minuteOfWeek, int minuteOfDay, LocalDate today) {

    public static SearchMoment now(Clock clock, ZoneId zone) {
        var instant = clock.instant();
        var local = instant.atZone(zone);
        var minuteOfDay = local.getHour() * 60 + local.getMinute();
        var minuteOfWeek = (local.getDayOfWeek().getValue() - 1) * 24 * 60 + minuteOfDay;
        return new SearchMoment(instant, minuteOfWeek, minuteOfDay, local.toLocalDate());
    }
}
