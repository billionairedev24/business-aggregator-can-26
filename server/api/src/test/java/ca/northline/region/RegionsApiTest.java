package ca.northline.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.payments.domain.CanadianTax;
import ca.northline.region.api.Regions;
import ca.northline.region.api.TaxRates;
import ca.northline.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** S-134: the region model as V130/V131 configure it — Alberta and its markets are the first live region, as data. */
class RegionsApiTest extends IntegrationTest {

    @Autowired
    Regions regions;

    @Autowired
    TaxRates taxes;

    @Test
    void theRegionsEndpointListsProvincesMarketsAndThePlatformZone() throws Exception {
        mvc.perform(get("/api/v1/geo/regions"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=300, public"))
                .andExpect(jsonPath("$.platformTimeZone").value("America/Edmonton"))
                .andExpect(jsonPath("$.defaultProvince").value("AB"))
                .andExpect(jsonPath("$.provinces[0].code").value("AB"))
                .andExpect(jsonPath("$.provinces[0].status").value("live"))
                .andExpect(jsonPath("$.provinces[0].timeZone").value("America/Edmonton"))
                .andExpect(jsonPath("$.provinces[0].privacyLaw").value("ab_pipa"))
                .andExpect(jsonPath("$.provinces[?(@.code == 'QC')].privacyLaw")
                        .value(org.hamcrest.Matchers.contains("qc_law25")))
                .andExpect(jsonPath("$.provinces.length()").value(13))
                .andExpect(jsonPath("$.markets[?(@.id == 'mkt-calgary')].city")
                        .value(org.hamcrest.Matchers.contains("Calgary")));
        mvc.perform(get("/api/v1/geo/regions?lang=fr"))
                .andExpect(jsonPath("$.provinces[?(@.code == 'BC')].name")
                        .value(org.hamcrest.Matchers.contains("Colombie-Britannique")));
    }

    @Test
    void albertaIsTheFirstConfiguredRegion() {
        var ab = regions.province("AB").orElseThrow();
        assertThat(ab.registries()).containsExactly("alberta_corporate_registry");
        assertThat(regions.market("Calgary", "AB").orElseThrow().registries())
                .containsExactly("calgary_business_licences");
        assertThat(regions.holidays("AB", 2026)).extracting(h -> h.key()).contains("heritage_day", "boxing_day");
        assertThat(regions.holidays("QC", 2026))
                .extracting(h -> h.key())
                .contains("saint_jean_baptiste", "patriots_day");
        assertThat(regions.holidays("MB", 2026)).extracting(h -> h.key()).contains("louis_riel_day");
        assertThat(regions.holiday("ON", LocalDate.of(2026, 8, 3))).isEmpty(); // civic holiday isn't statutory there
    }

    /** One tax regime: the region rows agree with S-21's per-province rates for every province and territory. */
    @Test
    void taxProfilesMatchTheStripeTaxRates() {
        for (var province : CanadianTax.Province.values()) {
            var percent = province.components().stream()
                    .map(CanadianTax.Component::percent)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            var bps = percent.movePointRight(2)
                    .setScale(0, java.math.RoundingMode.HALF_UP)
                    .intValueExact();
            assertThat(taxes.bpsFor(province.name())).as(province.name()).isEqualTo(bps);
            assertThat(regions.province(province.name()).orElseThrow().taxBps())
                    .as(province.name())
                    .isEqualTo(bps);
        }
    }
}
