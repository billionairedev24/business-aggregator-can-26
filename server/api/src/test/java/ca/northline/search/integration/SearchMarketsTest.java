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

/** Markets and their time zones are configuration (SEARCH_MARKETS), not code (DECISIONS "Region-neutral by design"). */
class SearchMarketsTest {

    @Test
    void parsesCodesAndZones_andRefusesMalformedEntries() {
        assertThat(SearchConfig.markets(" nb=America/Moncton, SK=America/Regina ,"))
                .containsExactly(
                        Map.entry("NB", ZoneId.of("America/Moncton")), Map.entry("SK", ZoneId.of("America/Regina")));
        assertThatThrownBy(() -> SearchConfig.markets("NB")).hasMessageContaining("CODE=Time/Zone");
        assertThatThrownBy(() -> SearchConfig.markets("NEW=America/Moncton")).hasMessageContaining("two-letter");
        assertThatThrownBy(() -> SearchConfig.markets("NB=Mars/Olympus"))
                .hasMessageContaining("NB has no valid time zone");
    }

    @Test
    void theDefaultMarketMustBeServed_andSomeMarketMustBe() {
        var markets = Map.of("NB", ZoneId.of("America/Moncton"));
        assertThat(new SearchSettings(Duration.ofSeconds(30), null, 120, markets).serves("NB"))
                .isTrue();
        assertThatThrownBy(() -> new SearchSettings(Duration.ofSeconds(30), "SK", 120, markets))
                .hasMessageContaining("SEARCH_DEFAULT_MARKET SK");
        assertThatThrownBy(() -> new SearchSettings(Duration.ofSeconds(30), null, 120, Map.of()))
                .hasMessageContaining("SEARCH_MARKETS");
    }

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
