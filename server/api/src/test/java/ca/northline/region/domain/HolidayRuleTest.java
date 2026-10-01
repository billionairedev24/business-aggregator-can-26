package ca.northline.region.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** S-134: holiday dates per rule (the provinces' calendars are region data: RegionCatalogueTest, RegionsApiTest). */
class HolidayRuleTest {

    @Test
    void datesOf2026() {
        assertThat(HolidayRule.FAMILY_DAY.in(2026)).isEqualTo(LocalDate.of(2026, 2, 16));
        assertThat(HolidayRule.LOUIS_RIEL_DAY.in(2026)).isEqualTo(LocalDate.of(2026, 2, 16));
        assertThat(HolidayRule.GOOD_FRIDAY.in(2026)).isEqualTo(LocalDate.of(2026, 4, 3));
        assertThat(HolidayRule.EASTER_MONDAY.in(2026)).isEqualTo(LocalDate.of(2026, 4, 6));
        assertThat(HolidayRule.VICTORIA_DAY.in(2026)).isEqualTo(LocalDate.of(2026, 5, 18));
        assertThat(HolidayRule.PATRIOTS_DAY.in(2026)).isEqualTo(LocalDate.of(2026, 5, 18));
        assertThat(HolidayRule.SAINT_JEAN_BAPTISTE.in(2026)).isEqualTo(LocalDate.of(2026, 6, 24));
        assertThat(HolidayRule.CIVIC_HOLIDAY.in(2026)).isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(HolidayRule.DISCOVERY_DAY.in(2026)).isEqualTo(LocalDate.of(2026, 8, 17));
        assertThat(HolidayRule.TRUTH_RECONCILIATION.in(2026)).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(HolidayRule.THANKSGIVING.in(2026)).isEqualTo(LocalDate.of(2026, 10, 12));
        // Victoria Day falls on May 24 itself when that is a Monday
        assertThat(HolidayRule.VICTORIA_DAY.in(2027)).isEqualTo(LocalDate.of(2027, 5, 24));
    }

    @Test
    void keysAndNames() {
        assertThat(HolidayRule.of("saint_jean_baptiste")).hasValue(HolidayRule.SAINT_JEAN_BAPTISTE);
        assertThat(HolidayRule.of("unknown")).isEmpty();
        assertThat(HolidayRule.FAMILY_DAY.name(Locale.CANADA_FRENCH)).isEqualTo("Fête de la famille");
        assertThat(HolidayRule.FAMILY_DAY.name(Locale.ENGLISH)).isEqualTo("Family Day");
    }
}
