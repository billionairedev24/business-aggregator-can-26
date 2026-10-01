package ca.northline.food.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Whether a kitchen takes orders at a moment, as customers see it (S-46/S-57): its opening hours for the day in the kitchen's
 * market time zone (a holiday entry replaces the weekday's hours), not paused (the Studio's 30-minute pause, or <b>auto-pause</b>: at
 * least {@code autoPauseLate} accepted orders past their promised ready time), and a live menu to order from.
 *
 * @param zone the time zone of the kitchen's market (region configuration), which its hours are kept in
 * @param week opening ranges per ISO weekday (1 = Monday); a missing day is closed
 * @param holidays replacement ranges for single dates (an empty list = closed that day)
 * @param autoPauseLate the Studio's "Auto-pause if late orders ≥" (3, 5, or null = never)
 * @param lateOrders accepted orders now past their ready-by time
 */
public record KitchenCalendar(
        ZoneId zone,
        Map<Integer, OpeningRanges> week,
        Map<LocalDate, OpeningRanges> holidays,
        @Nullable Instant pausedUntil,
        @Nullable Integer autoPauseLate,
        int lateOrders,
        boolean menuLive) {

    /** How far ahead "Opens …" looks. */
    private static final int LOOK_AHEAD_DAYS = 7;

    public KitchenCalendar {
        week = Map.copyOf(week);
        holidays = Map.copyOf(holidays);
    }

    /**
     * @param open takes orders now
     * @param opensAt when it opens next (closed now), or null (open, or nothing within a week)
     * @param closesAt when today's current range ends (open now)
     * @param paused closed only because of a pause (manual or automatic)
     */
    public record State(
            boolean open,
            @Nullable Instant opensAt,
            @Nullable Instant closesAt,
            boolean paused) {}

    public State at(Instant now) {
        if (!menuLive) {
            return new State(false, null, null, false);
        }
        var local = now.atZone(zone);
        var current = rangeAt(local.toLocalDate(), local.toLocalTime());
        var paused = KitchenPause.paused(pausedUntil, now) || autoPaused();
        if (current.isPresent() && !paused) {
            return new State(
                    true,
                    null,
                    local.toLocalDate().atTime(current.get().to()).atZone(zone).toInstant(),
                    false);
        }
        var resume = paused && !autoPaused() && pausedUntil != null ? pausedUntil : now;
        return new State(false, autoPaused() ? null : nextOpening(resume), null, paused && current.isPresent());
    }

    /** Length of a scheduled-order window (design 06: "Pick a 30-min window"). */
    public static final java.time.Duration WINDOW = java.time.Duration.ofMinutes(30);

    /**
     * Starts of the 30-minute windows a scheduled order may pick, today and the next {@code days - 1} days: inside the
     * opening ranges (the whole window), on the half hour, at least {@code lead} from now. Pauses don't matter (they
     * end long before), but a kitchen without a live menu offers none.
     */
    public java.util.List<Instant> slots(Instant now, java.time.Duration lead, int days) {
        var out = new java.util.ArrayList<Instant>();
        if (!menuLive) {
            return out;
        }
        var earliest = now.plus(lead);
        var today = now.atZone(KitchenTime.ZONE).toLocalDate();
        for (int d = 0; d < days; d++) {
            var date = today.plusDays(d);
            for (var range : day(date).ranges()) {
                var from = range.from().toSecondOfDay() / 60;
                var to = range.to().toSecondOfDay() / 60;
                for (var m = (from + 29) / 30 * 30; m + 30 <= to; m += 30) {
                    var start =
                            date.atTime(m / 60, m % 60).atZone(KitchenTime.ZONE).toInstant();
                    if (!start.isBefore(earliest)) {
                        out.add(start);
                    }
                }
            }
        }
        return out;
    }

    private boolean autoPaused() {
        return autoPauseLate != null && lateOrders >= autoPauseLate;
    }

    private OpeningRanges day(LocalDate date) {
        return Optional.ofNullable(holidays.get(date))
                .orElseGet(() -> week.getOrDefault(date.getDayOfWeek().getValue(), new OpeningRanges(List.of())));
    }

    private Optional<OpeningRanges.Range> rangeAt(LocalDate date, LocalTime time) {
        return day(date).ranges().stream()
                .filter(r -> !time.isBefore(r.from()) && time.isBefore(r.to()))
                .findFirst();
    }

    /** The first moment at or after {@code from} inside an opening range, within a week. */
    private @Nullable Instant nextOpening(Instant from) {
        var local = from.atZone(zone);
        if (rangeAt(local.toLocalDate(), local.toLocalTime()).isPresent()) {
            return from;
        }
        for (int d = 0; d <= LOOK_AHEAD_DAYS; d++) {
            var date = local.toLocalDate().plusDays(d);
            for (var range : day(date).ranges()) {
                var start = date.atTime(range.from()).atZone(zone).toInstant();
                if (start.isAfter(from)) {
                    return start;
                }
            }
        }
        return null;
    }
}
