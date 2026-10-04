package ca.northline.restricted.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AgeRecordTest {

    @Test
    void anAdultsAgeIsKeptCappedAtTheStrictestProvincialAge() {
        assertThat(AgeRecord.capped(47, 21)).isEqualTo(21);
        assertThat(AgeRecord.capped(19, 21)).isEqualTo(19);
        assertThat(AgeRecord.capped(-1, 21)).isZero();
    }

    @Test
    void theAgeFloorGrowsWithEveryWholeYearSinceTheCheck() {
        var on = LocalDate.of(2026, 3, 15);
        assertThat(AgeRecord.floor(18, on, on)).isEqualTo(18);
        assertThat(AgeRecord.floor(18, on, LocalDate.of(2027, 3, 14))).isEqualTo(18);
        assertThat(AgeRecord.floor(18, on, LocalDate.of(2027, 3, 15))).isEqualTo(19);
        assertThat(AgeRecord.floor(18, on, LocalDate.of(2029, 6, 1))).isEqualTo(21);
        // a clock behind the check never lowers it
        assertThat(AgeRecord.floor(18, on, LocalDate.of(2026, 1, 1))).isEqualTo(18);
    }
}
