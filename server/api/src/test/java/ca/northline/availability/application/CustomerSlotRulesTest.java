package ca.northline.availability.application;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.availability.domain.BookingRules;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** S-53/S-55: what a customer may book — notice, "same day by 9 am" and the horizon. */
class CustomerSlotRulesTest {

    /** Wednesday 30 Sep 2026, 08:00 in Calgary (MDT = UTC−6). */
    static final Instant NOW = Instant.parse("2026-09-30T14:00:00Z");

    static final LocalDate TODAY = LocalDate.parse("2026-09-30");

    /** The business's zone (test data: a Calgary business). */
    static final ZoneId ZONE = ZoneId.of("America/Edmonton");

    static BookingRules rules(int notice, Integer cutoff, int horizon) {
        var d = BookingRules.defaults();
        return new BookingRules(
                d.intervalMin(),
                d.bufferMin(),
                notice,
                cutoff,
                horizon,
                d.maxJobsPerDay(),
                d.acceptMode(),
                d.rescheduleFreeMin(),
                d.lateCancelFeeCents(),
                d.lateCancelFeeBps(),
                d.emergencyPremiumCents(),
                d.emergencyPremiumBps(),
                d.holidayPremiumCents(),
                d.serviceAreas());
    }

    @Test
    void minimumNotice() {
        var r = rules(60, null, 14);
        assertThat(CustomerSlotService.bookableTime(r, TODAY, NOW.plusSeconds(59 * 60), NOW, ZONE))
                .isFalse();
        assertThat(CustomerSlotService.bookableTime(r, TODAY, NOW.plusSeconds(60 * 60), NOW, ZONE))
                .isTrue();
    }

    @Test
    void sameDayByNine() {
        var r = rules(0, 540, 14);
        assertThat(CustomerSlotService.bookableTime(r, TODAY, NOW.plusSeconds(3 * 3600), NOW, ZONE))
                .isTrue();
        var afterNine = NOW.plusSeconds(90 * 60);
        assertThat(CustomerSlotService.bookableTime(r, TODAY, afterNine.plusSeconds(3600), afterNine, ZONE))
                .isFalse();
        assertThat(CustomerSlotService.bookableTime(r, TODAY.plusDays(1), NOW.plusSeconds(86_400), afterNine, ZONE))
                .isTrue();
    }

    @Test
    void horizonAndPast() {
        var r = rules(0, null, 14);
        assertThat(CustomerSlotService.bookableTime(r, TODAY.plusDays(13), NOW.plusSeconds(13 * 86_400), NOW, ZONE))
                .isTrue();
        assertThat(CustomerSlotService.bookableTime(r, TODAY.plusDays(14), NOW.plusSeconds(14 * 86_400), NOW, ZONE))
                .isFalse();
        assertThat(CustomerSlotService.bookableTime(r, TODAY.minusDays(1), NOW.minusSeconds(86_400), NOW, ZONE))
                .isFalse();
    }
}
