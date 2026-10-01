package ca.northline.region.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** Markets and their zones come from SEARCH_MARKETS / SEARCH_DEFAULT_MARKET (fictional zones keep the test neutral). */
class MarketSettingsTest {

    @Test
    void zonesComeFromConfigurationWithTheDefaultMarketForTheRest() {
        var markets = new MarketSettings("XA=Etc/GMT+7, xb=Etc/GMT+5", "XB");
        assertThat(markets.served()).containsExactly("XA", "XB");
        assertThat(markets.serves("xa")).isTrue();
        assertThat(markets.serves("ZZ")).isFalse();
        assertThat(markets.zone("XA")).isEqualTo(ZoneId.of("Etc/GMT+7"));
        assertThat(markets.zone("ZZ")).isEqualTo(ZoneId.of("Etc/GMT+5"));
        assertThat(markets.zone(null)).isEqualTo(ZoneId.of("Etc/GMT+5"));
        assertThat(markets.defaultProvince()).isEqualTo("XB");
        assertThat(new MarketSettings("XA=Etc/GMT+7", "").defaultProvince()).isNull();
        assertThat(new MarketSettings("XA=Etc/GMT+7", "").zone(null)).isEqualTo(ZoneId.of("Etc/GMT+7"));
    }

    @Test
    void malformedConfigurationStopsTheApi() {
        assertThatThrownBy(() -> new MarketSettings("", "")).hasMessageContaining("SEARCH_MARKETS");
        assertThatThrownBy(() -> new MarketSettings("Alpha=Etc/UTC", "")).hasMessageContaining("CODE=Time/Zone");
        assertThatThrownBy(() -> new MarketSettings("XA=Not/AZone", "")).hasMessageContaining("no valid time zone");
    }
}
