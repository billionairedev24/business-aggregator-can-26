package ca.northline.availability.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class AvailabilityDomainTest {

    @Test
    void albertaHolidays2026() {
        assertThat(AlbertaHolidays.of(2026))
                .extracting(AlbertaHolidays.Holiday::key, AlbertaHolidays.Holiday::date)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("family_day", LocalDate.of(2026, 2, 16)),
                        org.assertj.core.groups.Tuple.tuple("good_friday", LocalDate.of(2026, 4, 3)),
                        org.assertj.core.groups.Tuple.tuple("victoria_day", LocalDate.of(2026, 5, 18)),
                        org.assertj.core.groups.Tuple.tuple("thanksgiving", LocalDate.of(2026, 10, 12)));
        assertThat(AlbertaHolidays.upcoming(LocalDate.of(2026, 9, 8), 5))
                .extracting(AlbertaHolidays.Holiday::key)
                .containsExactly("thanksgiving", "remembrance_day", "christmas", "boxing_day", "new_year");
    }

    @Test
    void slotPreviewMatchesTheDesign() {
        // Ravi, Tuesday 7:00–18:00, 45-min brake inspection, 30-min interval, 20-min buffer, jobs 9:00–9:45 and
        // 11:30–12:00
        var slots = SlotPlanner.preview(
                List.of(new TimeRange(LocalTime.of(7, 0), LocalTime.of(18, 0))),
                List.of(new SlotPlanner.Busy(540, 585), new SlotPlanner.Busy(690, 720)),
                45,
                30,
                20);
        assertThat(slots).hasSize(21);
        assertThat(slots.stream().filter(SlotPlanner.Slot::free).count()).isEqualTo(12);
        assertThat(SlotPlanner.count(new TimeRange(LocalTime.of(9, 0), LocalTime.of(14, 0)), 45, 30))
                .isEqualTo(9);
    }
}
