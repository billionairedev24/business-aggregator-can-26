package ca.northline.console;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.region.api.Regions;
import ca.northline.shared.Ids;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** S-81: the delivery ops map's geometry — a market's centre and its delivery zones from the region model. */
class ConsoleDeliveryMapApiTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Regions regions;

    String staff;
    String market;

    @BeforeEach
    void market() {
        staff = data.user("Dee Dispatcher");
        market = "mkt-s81-" + Ids.next().toLowerCase(java.util.Locale.ROOT);
        jdbc.sql("""
                        insert into region.regions (id, kind, parent_id, province, city, name_i18n, stage, center, radius_km,
                                                    languages, sort)
                        values (:id, 'market', 'prov-nt', 'NT', :city, '{"en":"Mapville","fr":"Mapville"}', 'off',
                                'SRID=4326;POINT(-114.37 62.45)', 10, '{en,fr}', 99)""")
                .param("id", market)
                .param("city", "Mapville " + market.substring(8, 14))
                .update();
        zone(
                "Old Town",
                "POLYGON((-114.38 62.44, -114.36 62.44, -114.36 62.46, -114.38 62.46, -114.38 62.44))",
                2,
                299L);
        zone("Downtown", null, 1, 499L);
        regions.refresh();
    }

    void zone(String name, @Nullable String polygon, int runs, long fee) {
        jdbc.sql("""
                        insert into region.zones (id, region_id, name, polygon, runs_per_day, fee_std_cents, fee_plus_cents,
                                                  min_basket_cents, sort)
                        values (:id, :m, :n, ST_GeogFromText(:p), :r, :f, 0, 2500, :s)""")
                .param("id", Ids.next())
                .param("m", market)
                .param("n", name)
                .param("p", polygon == null ? null : "SRID=4326;" + polygon)
                .param("r", runs)
                .param("f", fee)
                .param("s", polygon == null ? 2 : 1)
                .update();
    }

    @Test
    void dispatchSeesTheMarketsZonesWithTheirOutlineAndPricing() throws Exception {
        mvc.perform(get("/api/v1/console/delivery/map")
                        .param("market", market)
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.market.id").value(market))
                .andExpect(jsonPath("$.market.province").value("NT"))
                .andExpect(jsonPath("$.market.lat").value(62.45))
                .andExpect(jsonPath("$.zones", hasSize(2)))
                .andExpect(jsonPath("$.zones[0].name").value("Old Town"))
                .andExpect(jsonPath("$.zones[0].ring", hasSize(5)))
                .andExpect(jsonPath("$.zones[0].ring[1].lng").value(-114.36))
                .andExpect(jsonPath("$.zones[0].ring[1].lat").value(62.44))
                .andExpect(jsonPath("$.zones[0].runsPerDay").value(2))
                .andExpect(jsonPath("$.zones[0].feeStdCents").value(299))
                .andExpect(jsonPath("$.zones[0].minBasketCents").value(2500))
                .andExpect(jsonPath("$.zones[1].name").value("Downtown"))
                .andExpect(jsonPath("$.zones[1].ring", hasSize(0)))
                .andExpect(jsonPath("$.basemap").value(nullValue()));
    }

    @Test
    void onlyRolesThatOpenDeliveryGetIt() throws Exception {
        mvc.perform(get("/api/v1/console/delivery/map")
                        .param("market", market)
                        .with(TestJwt.staff(staff, StaffRole.ADMIN)))
                .andExpect(status().isOk());
        for (var role : new StaffRole[] {StaffRole.SUPPORT, StaffRole.FINANCE, StaffRole.TRUST_SAFETY}) {
            mvc.perform(get("/api/v1/console/delivery/map")
                            .param("market", market)
                            .with(TestJwt.staff(staff, role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
        }
        mvc.perform(get("/api/v1/console/delivery/map")
                        .param("market", market)
                        .with(TestJwt.staffWithoutMfa(staff, StaffRole.DISPATCH)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
    }

    @Test
    void anUnknownMarketIsA422() throws Exception {
        mvc.perform(get("/api/v1/console/delivery/map")
                        .param("market", "mkt-nowhere")
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose a market from the list."));
    }
}
