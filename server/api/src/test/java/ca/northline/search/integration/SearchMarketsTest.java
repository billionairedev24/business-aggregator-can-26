package ca.northline.search.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.search.application.SearchMoment;
import ca.northline.search.application.SearchSettings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** "Now" is the market's local time; markets and their zones are the region model's (S-134, RegionCatalogueTest). */
class SearchMarketsTest {

    @Test
    void nowIsTheMarketsLocalTime() {
        // Wednesday 2026-09-30 15:30 UTC
        var clock = Clock.fixed(Instant.parse("2026-09-30T15:30:00Z"), ZoneOffset.UTC);
        var atlantic = SearchMoment.now(clock, ZoneId.of("America/Moncton")); // UTC−3 in September: 12:30
        var pacific = SearchMoment.now(clock, ZoneId.of("America/Vancouver")); // UTC−7: 08:30
        assertThat(atlantic.minuteOfDay()).isEqualTo(12 * 60 + 30);
        assertThat(pacific.minuteOfDay()).isEqualTo(8 * 60 + 30);
        assertThat(atlantic.minuteOfWeek()).isEqualTo(2 * 24 * 60 + 12 * 60 + 30);
        assertThat(atlantic.today()).isEqualTo(pacific.today());
    }
}
