package ca.northline.availability.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class AvailabilityDomainTest {

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
