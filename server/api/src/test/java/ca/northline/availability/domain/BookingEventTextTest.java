package ca.northline.availability.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** What a booking looks like in the member's own calendar (S-32): the design's first name and address, nothing more. */
class BookingEventTextTest {

    static final String LINK = "https://studio.northline.ca/b/01M/appointments";

    @Test
    void serviceFirstNameAddressAndReference() {
        var text = BookingEventText.of("Brake inspection", "Amara", "1204 17 Ave SW", "BK-1234", LINK);
        assertThat(text.summary()).isEqualTo("Brake inspection · Amara");
        assertThat(text.location()).isEqualTo("1204 17 Ave SW");
        assertThat(text.description()).isEqualTo("Northline booking BK-1234 · réservation Northline\n" + LINK);
    }

    @Test
    void withoutACustomerOrAddress() {
        var text = BookingEventText.of("Oil change", " ", null, null, LINK);
        assertThat(text.summary()).isEqualTo("Oil change");
        assertThat(text.location()).isNull();
        assertThat(text.description()).startsWith("Northline booking · ");
    }

    @Test
    void theHashChangesWithTimeOrText_only() {
        var t0 = Instant.parse("2026-10-02T15:00:00Z");
        var t1 = Instant.parse("2026-10-02T16:00:00Z");
        var a = BookingEventText.of("Brake inspection", "Amara", "1204 17 Ave SW", "BK-1", LINK);
        assertThat(a.hash(t0, t1)).isEqualTo(a.hash(t0, t1));
        assertThat(a.hash(t0, t1)).isNotEqualTo(a.hash(t0, t1.plusSeconds(1800)));
        assertThat(a.hash(t0, t1))
                .isNotEqualTo(BookingEventText.of("Brake inspection", "Amara", "1 Main St", "BK-1", LINK)
                        .hash(t0, t1));
    }
}
