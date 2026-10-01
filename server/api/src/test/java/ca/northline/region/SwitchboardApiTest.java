package ca.northline.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.region.api.LaunchStatus;
import ca.northline.region.api.Regions;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * S-84: turning a province on from the console's switchboard — the checklist, the confirmation, markets that never
 * exceed their province, delivery zones from GeoJSON, the audit log and the region model re-read at once. Uses Prince
 * Edward Island (off in V130, no other test opens it) and puts it back after each test.
 */
class SwitchboardApiTest extends IntegrationTest {

    static final String PE = "PE";
    static final String POLYGON =
            "{\"type\":\"Polygon\",\"coordinates\":[[[-63.15,46.22],[-63.10,46.22],[-63.10,46.25],[-63.15,46.25],[-63.15,46.22]]]}";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Regions regions;

    String admin;

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @BeforeEach
    void staff() {
        admin = data.user("Lena Admin");
        reset();
    }

    @AfterEach
    void reset() {
        jdbc.sql(
                        "delete from region.zones where region_id in (select id from region.regions where parent_id = 'prov-pe')")
                .update();
        jdbc.sql("delete from region.regions where parent_id = 'prov-pe'").update();
        jdbc.sql(
                        "update region.regions set stage = 'off', registries = '{}', courier_model = null where id = 'prov-pe'")
                .update();
        regions.refresh();
    }

    long audited(String action, String target) {
        return jdbc.sql("""
                        select count(*) from developer.audit_log
                         where action = ? and target_id = ? and actor_id = ? and merchant_id is null""").params(action, target, admin).query(Long.class).single();
    }

    String market(String city) throws Exception {
        var body = mvc.perform(json(
                                post("/api/v1/console/regions/markets"),
                                "{\"province\":\"PE\",\"city\":\"%s\",\"lat\":46.238,\"lng\":-63.131,\"radiusKm\":15}"
                                        .formatted(city))
                        .with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.<java.util.List<String>>read(body, "$.markets[?(@.city == '%s')].id".formatted(city))
                .getFirst();
    }

    @Test
    void aProvinceGoesLiveOnceItsChecklistIsDone() throws Exception {
        mvc.perform(get("/api/v1/console/regions").with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provinces[?(@.code == 'PE')].stage").value("off"))
                .andExpect(jsonPath("$.provinces[?(@.code == 'PE')].tax.hst").value(1500))
                .andExpect(jsonPath("$.provinces[?(@.code == 'PE')].checklist.taxProfile")
                        .value(true))
                .andExpect(jsonPath("$.provinces[?(@.code == 'PE')].checklist.holidays")
                        .value(true))
                .andExpect(jsonPath("$.provinces[?(@.code == 'PE')].checklist.registries")
                        .value(false))
                .andExpect(jsonPath("$.provinces[?(@.code == 'PE')].checklist.marketWithZones")
                        .value(false));

        // not ready: no registries, no market with zones
        mvc.perform(json(post("/api/v1/console/regions/provinces/PE/stage"), "{\"stage\":\"live\",\"confirm\":\"PE\"}")
                        .with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_ready"));

        // pilot needs no checklist; a market can follow up to the province's stage, not above
        mvc.perform(json(post("/api/v1/console/regions/provinces/PE/stage"), "{\"stage\":\"pilot\",\"confirm\":\"pe\"}")
                        .with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("pilot"));
        assertThat(regions.province(PE).orElseThrow().status()).isEqualTo(LaunchStatus.PILOT);
        assertThat(audited("region.stage_changed", "prov-pe")).isEqualTo(1);

        var market = market("Charlottetown");
        assertThat(audited("region.market_added", market)).isEqualTo(1);
        assertThat(regions.marketById(market)).isPresent();
        mvc.perform(json(
                                post("/api/v1/console/regions/markets/{id}/stage", market),
                                "{\"stage\":\"live\",\"confirm\":\"Charlottetown\"}")
                        .with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("stage"))
                .andExpect(jsonPath("$.errors[0].message").value("A market can't be more open than its province."));
        mvc.perform(json(
                                post("/api/v1/console/regions/markets/{id}/stage", market),
                                "{\"stage\":\"pilot\",\"confirm\":\"charlottetown\"}")
                        .with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markets[0].stage").value("pilot"));

        // a zone from GeoJSON (a Feature works too), then the registry: the checklist is done
        mvc.perform(json(
                                post("/api/v1/console/regions/zones"),
                                "{\"marketId\":\"%s\",\"name\":\"Downtown\",\"runsPerDay\":2,\"feeStdCents\":299,\"feePlusCents\":0,\"minBasketCents\":2500,\"boundary\":%s}"
                                        .formatted(
                                                market,
                                                quote("{\"type\":\"Feature\",\"properties\":{},\"geometry\":" + POLYGON
                                                        + "}")))
                        .with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zones", hasSize(1)))
                .andExpect(jsonPath("$.zones[0].name").value("Downtown"))
                .andExpect(jsonPath("$.zones[0].areaKm2").isNumber())
                .andExpect(jsonPath("$.checklist.marketWithZones").value(true));
        jdbc.sql("update region.regions set registries = '{manual}' where id = 'prov-pe'")
                .update();
        mvc.perform(json(post("/api/v1/console/regions/provinces/PE/stage"), "{\"stage\":\"live\",\"confirm\":\"PE\"}")
                        .with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("live"));
        assertThat(regions.province(PE).orElseThrow().status()).isEqualTo(LaunchStatus.LIVE);
        mvc.perform(get("/api/v1/geo/regions?lang=en"))
                .andExpect(jsonPath("$.provinces[?(@.code == 'PE')].status").value("live"));

        // lowering the province brings its markets down with it
        mvc.perform(json(
                                post("/api/v1/console/regions/provinces/PE/stage"),
                                "{\"stage\":\"waitlist\",\"confirm\":\"PE\"}")
                        .with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markets[0].stage").value("waitlist"));
        assertThat(jdbc.sql("""
                        select after -> 'marketsLowered' ->> ? from developer.audit_log
                         where action = 'region.stage_changed' and target_id = 'prov-pe' and after ->> 'stage' = 'waitlist'""").params(market).query(String.class).single()).isEqualTo("pilot");
    }

    @Test
    void zonesAreEditedAndRemovedButALiveMarketKeepsOne() throws Exception {
        jdbc.sql("update region.regions set stage = 'live', registries = '{manual}' where id = 'prov-pe'")
                .update();
        var market = market("Summerside");
        var created = mvc.perform(json(
                                post("/api/v1/console/regions/zones"),
                                "{\"marketId\":\"%s\",\"name\":\"Waterfront\",\"boundary\":%s}"
                                        .formatted(market, quote(POLYGON)))
                        .with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String zone = JsonPath.<java.util.List<String>>read(created, "$.zones[?(@.name == 'Waterfront')].id")
                .getFirst();
        mvc.perform(json(
                                put("/api/v1/console/regions/zones/{id}", zone),
                                "{\"marketId\":\"%s\",\"name\":\"Waterfront East\",\"feeStdCents\":399}"
                                        .formatted(market))
                        .with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zones[0].name").value("Waterfront East"))
                .andExpect(jsonPath("$.zones[0].feeStdCents").value(399))
                .andExpect(jsonPath("$.zones[0].areaKm2").isNumber());
        assertThat(audited("region.zone_updated", zone)).isEqualTo(1);
        mvc.perform(json(
                                post("/api/v1/console/regions/markets/{id}/stage", market),
                                "{\"stage\":\"live\",\"confirm\":\"Summerside\"}")
                        .with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/console/regions/zones/{id}", zone).with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("last_zone"));
        mvc.perform(json(
                                post("/api/v1/console/regions/markets/{id}/stage", market),
                                "{\"stage\":\"pilot\",\"confirm\":\"Summerside\"}")
                        .with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/console/regions/zones/{id}", zone).with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zones", hasSize(0)));
        assertThat(audited("region.zone_removed", zone)).isEqualTo(1);
    }

    @Test
    void validationAndTheConfirmationStep() throws Exception {
        mvc.perform(json(post("/api/v1/console/regions/provinces/PE/stage"), "{\"stage\":\"pilot\",\"confirm\":\"NS\"}")
                        .with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("confirm"))
                .andExpect(jsonPath("$.errors[0].message").value("Type the province code to confirm."));
        mvc.perform(json(post("/api/v1/console/regions/provinces/PE/stage"), "{\"stage\":\"open\",\"confirm\":\"PE\"}")
                        .with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose off, waitlist, pilot or live."));
        mvc.perform(json(put("/api/v1/console/regions/provinces/PE/courier-model"), "{\"courierModel\":\"drones\"}")
                        .with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose own fleet, contracted or hybrid."));
        mvc.perform(json(put("/api/v1/console/regions/provinces/PE/courier-model"), "{\"courierModel\":\"hybrid\"}")
                        .with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courierModel").value("hybrid"));
        mvc.perform(json(
                                post("/api/v1/console/regions/markets"),
                                "{\"province\":\"PE\",\"city\":\"X\",\"lat\":46.2,\"lng\":-63.1,\"radiusKm\":10}")
                        .with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Enter the city or area, 2 to 60 characters."));
        mvc.perform(json(
                                post("/api/v1/console/regions/markets"),
                                "{\"province\":\"PE\",\"city\":\"Swapped\",\"lat\":-63.1,\"lng\":46.2,\"radiusKm\":10}")
                        .with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message")
                        .value("Give the market's centre as a latitude and longitude in Canada."));
        var market = market("Montague");
        mvc.perform(json(
                                post("/api/v1/console/regions/markets"),
                                "{\"province\":\"PE\",\"city\":\"montague\",\"lat\":46.2,\"lng\":-62.6,\"radiusKm\":10}")
                        .with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("market_exists"));
        mvc.perform(json(
                                post("/api/v1/console/regions/zones"),
                                "{\"marketId\":\"%s\",\"name\":\"Bad\",\"boundary\":\"{not json\"}".formatted(market))
                        .with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("boundary"))
                .andExpect(
                        jsonPath("$.errors[0].message").value("Paste a GeoJSON polygon (lng, lat) for the boundary."));
        mvc.perform(json(
                                post("/api/v1/console/regions/zones"),
                                "{\"marketId\":\"%s\",\"name\":\"Runs\",\"runsPerDay\":30}".formatted(market))
                        .with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Pooled runs a day are 0 to 24."));
        mvc.perform(json(
                                post("/api/v1/console/regions/markets/{id}/stage", market),
                                "{\"stage\":\"off\",\"confirm\":\"Charlottetown\"}")
                        .with(TestJwt.staff(admin, StaffRole.ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Type the market's name to confirm."));
    }

    @Test
    void onlyAdminsOpenTheSwitchboard() throws Exception {
        for (var role : new StaffRole[] {
            StaffRole.TRUST_SAFETY, StaffRole.DISPATCH, StaffRole.FINANCE, StaffRole.SUPPORT, StaffRole.ANALYST
        }) {
            mvc.perform(get("/api/v1/console/regions").with(TestJwt.staff(admin, role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
            mvc.perform(json(
                                    post("/api/v1/console/regions/provinces/PE/stage"),
                                    "{\"stage\":\"pilot\",\"confirm\":\"PE\"}")
                            .with(TestJwt.staff(admin, role)))
                    .andExpect(status().isForbidden());
            mvc.perform(json(
                                    post("/api/v1/console/regions/markets"),
                                    "{\"province\":\"PE\",\"city\":\"Nope\",\"lat\":46.2,\"lng\":-63.1,\"radiusKm\":10}")
                            .with(TestJwt.staff(admin, role)))
                    .andExpect(status().isForbidden());
            mvc.perform(delete("/api/v1/console/regions/zones/{id}", "z").with(TestJwt.staff(admin, role)))
                    .andExpect(status().isForbidden());
            mvc.perform(json(put("/api/v1/console/regions/provinces/PE/courier-model"), "{\"courierModel\":\"own\"}")
                            .with(TestJwt.staff(admin, role)))
                    .andExpect(status().isForbidden());
            mvc.perform(json(
                                    post("/api/v1/console/regions/markets/{id}/stage", "m"),
                                    "{\"stage\":\"pilot\",\"confirm\":\"x\"}")
                            .with(TestJwt.staff(admin, role)))
                    .andExpect(status().isForbidden());
            mvc.perform(json(post("/api/v1/console/regions/zones"), "{}").with(TestJwt.staff(admin, role)))
                    .andExpect(status().isForbidden());
            mvc.perform(json(put("/api/v1/console/regions/zones/{id}", "z"), "{}")
                            .with(TestJwt.staff(admin, role)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(json(post("/api/v1/console/regions/provinces/PE/stage"), "{\"stage\":\"pilot\",\"confirm\":\"PE\"}")
                        .with(TestJwt.staffWithoutMfa(admin, StaffRole.ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
        // an admin acting in another role view is refused too
        mvc.perform(json(post("/api/v1/console/regions/provinces/PE/stage"), "{\"stage\":\"pilot\",\"confirm\":\"PE\"}")
                        .header("X-Console-Role", "finance")
                        .with(TestJwt.staff(admin, StaffRole.ADMIN, StaffRole.FINANCE)))
                .andExpect(status().isForbidden());
        assertThat(regions.province(PE).orElseThrow().status()).isEqualTo(LaunchStatus.OFF);
    }

    static String quote(String json) {
        return "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
