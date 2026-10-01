package ca.northline.region.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.region.api.LaunchStatus;
import ca.northline.region.api.PrivacyLaw;
import ca.northline.region.application.MarketStore.ProfileRow;
import ca.northline.region.application.MarketStore.RegionRow;
import ca.northline.region.application.MarketStore.ZoneRow;
import ca.northline.region.domain.GeoPoint;
import ca.northline.region.domain.Stage;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * S-134: the region model from rows plus configuration — fictional provinces (XA, XB) and zones keep it neutral, and
 * opening a province is a row or a setting, never code.
 */
class RegionCatalogueTest {

    static final ZoneId PLATFORM = ZoneId.of("Etc/GMT+7");

    /** In-memory rows: what the console (or a migration) writes into region.regions. */
    static final class Rows implements MarketStore {
        final List<ProfileRow> rows = new ArrayList<>();

        void province(String code, Stage stage, String zone, String... holidays) {
            rows.add(new ProfileRow(
                    new RegionRow(
                            "prov-" + code,
                            "province",
                            null,
                            code,
                            null,
                            "Name " + code,
                            "Nom " + code,
                            stage,
                            null,
                            null),
                    List.of(zone),
                    List.of(holidays),
                    "pipeda",
                    List.of("registry_" + code.toLowerCase(java.util.Locale.ROOT)),
                    500));
        }

        void market(String id, String province, String city, Stage stage, @Nullable String zone) {
            rows.add(new ProfileRow(
                    new RegionRow(id, "market", "prov-" + province, province, city, city, city, stage, 1.0, 2.0),
                    zone == null ? List.of() : List.of(zone),
                    List.of(),
                    null,
                    List.of("licences_" + city.toLowerCase(java.util.Locale.ROOT)),
                    null));
        }

        @Override
        public List<ProfileRow> profiles() {
            return List.copyOf(rows);
        }

        @Override
        public List<RegionRow> regions() {
            return rows.stream().map(ProfileRow::region).toList();
        }

        @Override
        public Optional<RegionRow> marketAt(GeoPoint point) {
            return Optional.empty();
        }

        @Override
        public Optional<RegionRow> nearestMarket(String province, GeoPoint point) {
            return Optional.empty();
        }

        @Override
        public Optional<RegionRow> province(String code) {
            return Optional.empty();
        }

        @Override
        public Optional<RegionRow> region(String id) {
            return Optional.empty();
        }

        @Override
        public Optional<ZoneRow> zoneAt(String marketId, GeoPoint point) {
            return Optional.empty();
        }

        @Override
        public boolean joinWaitlist(
                String id, String regionId, @Nullable String userId, @Nullable String email, String locale) {
            return false;
        }
    }

    static RegionCatalogue catalogue(Rows rows, String provinces, String defaultProvince) {
        return new RegionCatalogue(
                rows, new RegionProperties(provinces, defaultProvince, PLATFORM, Duration.ofMinutes(1)));
    }

    @Test
    void liveRowsAreServed_withTheirZonesHolidaysAndRegistries() {
        var rows = new Rows();
        rows.province("XA", Stage.LIVE, "Etc/GMT+6", "new_year", "family_day");
        rows.province("XB", Stage.OFF, "Etc/GMT+4", "new_year", "saint_jean_baptiste");
        rows.market("mkt-a", "XA", "Alphaville", Stage.LIVE, null);
        var regions = catalogue(rows, "", "XA");

        assertThat(regions.served()).containsExactly("XA");
        assertThat(regions.province("xa").orElseThrow().status()).isEqualTo(LaunchStatus.LIVE);
        assertThat(regions.province("XA").orElseThrow().privacyLaw()).isEqualTo(PrivacyLaw.PIPEDA);
        assertThat(regions.zone("XA", "alphaville")).isEqualTo(ZoneId.of("Etc/GMT+6")); // the market keeps XA's
        assertThat(regions.zone(null, null)).isEqualTo(PLATFORM);
        assertThat(regions.platformZone()).isEqualTo(PLATFORM);
        assertThat(regions.holidays("XA", 2026)).extracting(h -> h.key()).containsExactly("new_year", "family_day");
        assertThat(regions.holiday("XB", LocalDate.of(2026, 6, 24))).isPresent();
        assertThat(regions.holiday("XA", LocalDate.of(2026, 6, 24))).isEmpty();
        assertThat(regions.registries("XA", "Alphaville")).containsExactly("registry_xa", "licences_alphaville");
        assertThat(regions.registries("XB", "Alphaville")).containsExactly("registry_xb");
        assertThat(regions.provinceName("XB", java.util.Locale.CANADA_FRENCH)).isEqualTo("Nom XB");
    }

    /** A second province by configuration alone (REGION_PROVINCES), with a zone that replaces the row's. */
    @Test
    void aSecondProvinceIsConfigurationOnly() {
        var rows = new Rows();
        rows.province("XA", Stage.LIVE, "Etc/GMT+6");
        rows.province("XB", Stage.OFF, "Etc/GMT+4");
        var regions = catalogue(rows, "XB=Etc/GMT+3", "XA");

        assertThat(regions.served()).containsExactly("XB", "XA");
        assertThat(regions.province("XB").orElseThrow().status()).isEqualTo(LaunchStatus.LIVE);
        assertThat(regions.zone("XB")).isEqualTo(ZoneId.of("Etc/GMT+3"));
        assertThat(regions.province("XB").orElseThrow().timeZones())
                .containsExactly(ZoneId.of("Etc/GMT+3"), ZoneId.of("Etc/GMT+4"));
        // an unserved or unknown province: the default province's zone
        assertThat(regions.zone("ZZ")).isEqualTo(ZoneId.of("Etc/GMT+6"));
    }

    @Test
    void rowsAreReadAgainOnRefresh() {
        var rows = new Rows();
        rows.province("XA", Stage.LIVE, "Etc/GMT+6");
        var regions = catalogue(rows, "", "");
        assertThat(regions.markets()).isEmpty();
        rows.market("mkt-a", "XA", "Alphaville", Stage.LIVE, "Etc/GMT+5");
        assertThat(regions.markets()).isEmpty(); // cached
        regions.refresh();
        assertThat(regions.market("ALPHAVILLE", null).orElseThrow().zone()).isEqualTo(ZoneId.of("Etc/GMT+5"));
        assertThat(regions.defaultProvince()).isNull();
    }

    @Test
    void malformedConfigurationStopsTheApi() {
        var rows = new Rows();
        assertThatThrownBy(() -> catalogue(rows, "Alpha", "")).hasMessageContaining("two-letter");
        assertThatThrownBy(() -> catalogue(rows, "XA=Not/AZone", "")).hasMessageContaining("no valid time zone");
        assertThatThrownBy(() -> catalogue(rows, "", "Alpha")).hasMessageContaining("REGION_DEFAULT_PROVINCE");
    }
}
