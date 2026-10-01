package ca.northline.food.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class KitchenCalendarTest {

    // Wednesday 30 Sep 2026, Edmonton
    private static final LocalDate WED = LocalDate.of(2026, 9, 30);

    private static OpeningRanges ranges(String from, String to) {
        return new OpeningRanges(List.of(new OpeningRanges.Range(LocalTime.parse(from), LocalTime.parse(to))));
    }

    private static java.time.Instant at(LocalDate day, String time) {
        return LocalDateTime.of(day, LocalTime.parse(time))
                .atZone(KitchenTime.ZONE)
                .toInstant();
    }

    private static KitchenCalendar calendar(
            Map<LocalDate, OpeningRanges> holidays,
            java.time.@Nullable Instant pausedUntil,
            @Nullable Integer autoPause,
            int late,
            boolean menuLive) {
        var week = Map.of(1, ranges("11:00", "21:00"), 3, ranges("11:00", "21:00"), 4, ranges("11:00", "21:00"));
        return new KitchenCalendar(KitchenTime.ZONE, week, holidays, pausedUntil, autoPause, late, menuLive);
    }

    private static KitchenCalendar open() {
        return calendar(Map.of(), null, 3, 0, true);
    }

    @Test
    void openInsideTodaysHoursUntilTheRangeEnds() {
        var state = open().at(at(WED, "18:30"));
        assertThat(state.open()).isTrue();
        assertThat(state.closesAt()).isEqualTo(at(WED, "21:00"));
        assertThat(state.opensAt()).isNull();
    }

    @Test
    void beforeOpeningSaysWhenItOpensAndAfterClosingPointsToTheNextOpenDay() {
        assertThat(open().at(at(WED, "09:00")).opensAt()).isEqualTo(at(WED, "11:00"));
        assertThat(open().at(at(WED, "21:00")).open()).isFalse();
        // Thursday is open; Friday–Sunday closed, Monday open
        assertThat(open().at(at(WED.plusDays(1), "22:00")).opensAt()).isEqualTo(at(WED.plusDays(5), "11:00"));
    }

    @Test
    void holidayHoursReplaceTheWeekday() {
        var closed = calendar(Map.of(WED, new OpeningRanges(List.of())), null, null, 0, true);
        assertThat(closed.at(at(WED, "12:00")).open()).isFalse();
        assertThat(closed.at(at(WED, "12:00")).opensAt()).isEqualTo(at(WED.plusDays(1), "11:00"));
        var sunday = WED.plusDays(4);
        var special = calendar(Map.of(sunday, ranges("10:00", "14:00")), null, null, 0, true);
        assertThat(special.at(at(sunday, "12:00")).open()).isTrue();
    }

    @Test
    void aPausedKitchenIsClosedUntilThePauseEnds() {
        var until = at(WED, "12:30");
        var state = calendar(Map.of(), until, null, 0, true).at(at(WED, "12:10"));
        assertThat(state.open()).isFalse();
        assertThat(state.paused()).isTrue();
        assertThat(state.opensAt()).isEqualTo(until);
        assertThat(calendar(Map.of(), until, null, 0, true).at(at(WED, "12:31")).open())
                .isTrue();
    }

    @Test
    void autoPauseStopsOrdersOnceEnoughAreLate() {
        assertThat(calendar(Map.of(), null, 3, 2, true).at(at(WED, "12:00")).open())
                .isTrue();
        var state = calendar(Map.of(), null, 3, 3, true).at(at(WED, "12:00"));
        assertThat(state.open()).isFalse();
        assertThat(state.paused()).isTrue();
        assertThat(state.opensAt()).isNull(); // resumes when the kitchen catches up, not at a known time
        assertThat(calendar(Map.of(), null, null, 9, true).at(at(WED, "12:00")).open())
                .isTrue(); // "Never"
    }

    @Test
    void withoutALiveMenuNothingCanBeOrdered() {
        var state = calendar(Map.of(), null, null, 0, false).at(at(WED, "12:00"));
        assertThat(state.open()).isFalse();
        assertThat(state.opensAt()).isNull();
    }

    @Test
    void scheduledWindowsAreWholeHalfHoursInsideTheHoursAfterTheLead() {
        // Wednesday 20:05 + 45 min = 20:50: nothing left today; Thursday 11:00–21:00 has 20 windows
        var late = open().slots(at(WED, "20:05"), java.time.Duration.ofMinutes(45), 2);
        assertThat(late).first().isEqualTo(at(WED.plusDays(1), "11:00"));
        assertThat(late).last().isEqualTo(at(WED.plusDays(1), "20:30"));
        assertThat(late).hasSize(20);
        // 18:10 + 45 min = 18:55 → 19:00, 19:30, 20:00, 20:30 today
        var evening = open().slots(at(WED, "18:10"), java.time.Duration.ofMinutes(45), 1);
        assertThat(evening).containsExactly(at(WED, "19:00"), at(WED, "19:30"), at(WED, "20:00"), at(WED, "20:30"));
    }

    @Test
    void noWindowsWithoutALiveMenu() {
        assertThat(calendar(Map.of(), null, null, 0, false).slots(at(WED, "09:00"), java.time.Duration.ZERO, 2))
                .isEmpty();
    }
}
