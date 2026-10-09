package ca.northline.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.availability.api.ProviderSlots;
import ca.northline.orders.api.DeliveryRuns;
import ca.northline.region.api.MerchantPlaces;
import ca.northline.region.api.Regions;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.OperationsFixtures;
import ca.northline.support.TestJwt;
import ca.northline.tools.CategorySeeder;
import com.jayway.jsonpath.JsonPath;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * S-134: opening a second province is configuration only. Saskatchewan — {@code off} in V117, no market, no code
 * anywhere naming it — is opened with region rows alone (its row live, a Saskatoon market, the market's service zones),
 * exactly what the console will write. A provider and a kitchen there then get, end to end: Saskatchewan's holiday
 * calendar and its name in messages, Saskatoon's time zone in hours, slots and delivery cut-offs, Saskatchewan's tax
 * on quotes, the market's service zones, and registry checks routed by province and city (no Saskatchewan adapter →
 * an agent; no Saskatoon licence source → no municipal lookup). Alberta's tests stay as they were.
 */
class SecondProvinceTest extends IntegrationTest {

    static final ZoneId REGINA = ZoneId.of("America/Regina");
    static final String MARKET = "mkt-test-saskatoon";

    static final String QUOTE = """
            {"lines":[
               {"kind":"labour","description":"Diagnose charging system","qty":0.5,"unitCents":6500},
               {"kind":"part","description":"Alternator — remanufactured, 12-mo warranty","qty":1,"unitCents":24000}],
             "scope":"Confirm charging fault, replace alternator.","exclusions":"Belt extra if worn.",
             "durationMin":120,"validHours":72,"warranty":"parts_labour_12m","depositKind":"parts_upfront"}
            """;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    Regions regions;

    @Autowired
    MerchantPlaces places;

    @Autowired
    ProviderSlots slots;

    @Autowired
    DeliveryRuns runs;

    String merchantId;
    String owner;

    @BeforeEach
    void openSaskatchewan() {
        jdbc.sql("update region.regions set stage = 'live' where kind = 'province' and province = 'SK'")
                .update();
        jdbc.sql("""
                        insert into region.regions (id, kind, parent_id, province, city, name_i18n, stage, center,
                                                    radius_km, languages, sort)
                        values (:id, 'market', 'prov-sk', 'SK', 'Saskatoon', '{"en":"Saskatoon","fr":"Saskatoon"}',
                                'live', 'SRID=4326;POINT(-106.6700 52.1332)', 20, '{en,fr}', 1)
                        on conflict (id) do nothing
                        """).param("id", MARKET).update();
        jdbc.sql("""
                        insert into availability.service_zones (name, city, centre, area, radius_m, market_id, sort,
                                                                default_on)
                        select z.name, 'Saskatoon', ST_SetSRID(ST_MakePoint(z.lng, z.lat), 4326)::geography,
                               ST_Buffer(ST_SetSRID(ST_MakePoint(z.lng, z.lat), 4326)::geography, 1500)::geography(Polygon),
                               1500, :market, z.sort, z.dflt
                          from (values ('Nutana', 52.1200, -106.6500, 1, true),
                                       ('Riversdale', 52.1270, -106.6800, 2, false)) as z(name, lat, lng, sort, dflt)
                        on conflict (name) do nothing
                        """).param("market", MARKET).update();
        regions.refresh();

        merchantId = data.merchant("provider", "Prairie Spark Electric");
        owner = data.user("Saskia Owner");
        data.member(merchantId, owner, MerchantRole.OWNER);
        jdbc.sql("update merchants.merchants set province = 'SK', city = 'Saskatoon' where id = ?")
                .params(merchantId)
                .update();
        jdbc.sql("update merchants.merchant_members set bookable = true where merchant_id = ?")
                .params(merchantId)
                .update();
    }

    /** Back to V117's state, so the shared database looks the same to every other test class. */
    @AfterEach
    void closeSaskatchewan() {
        jdbc.sql("update region.regions set stage = 'off' where kind = 'province' and province = 'SK'")
                .update();
        regions.refresh();
    }

    String path(String rest) {
        return "/api/v1/merchants/" + merchantId + rest;
    }

    @Test
    void theProvinceIsServedWithItsOwnFacts_fromRowsAlone() {
        var sk = regions.province("SK").orElseThrow();
        assertThat(sk.status().live()).isTrue();
        assertThat(sk.zone()).isEqualTo(REGINA);
        assertThat(sk.taxBps()).isEqualTo(1100); // GST 5 % + PST 6 %
        assertThat(sk.privacyLaw().code()).isEqualTo("pipeda");
        assertThat(sk.registries()).isEmpty();
        assertThat(regions.market("saskatoon", "SK").orElseThrow().zone()).isEqualTo(REGINA);

        var place = places.of(merchantId);
        assertThat(place.province()).isEqualTo("SK");
        assertThat(place.ownProvince()).isTrue();
        assertThat(place.marketId()).isEqualTo(MARKET);
        assertThat(place.zone()).isEqualTo(REGINA);
        assertThat(place.provinceNameEn()).isEqualTo("Saskatchewan");
    }

    @Test
    void theStudioHeaderCarriesThePlace() throws Exception {
        mvc.perform(get(path("")).with(TestJwt.member(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.region.province").value("SK"))
                .andExpect(jsonPath("$.region.provinceName.en").value("Saskatchewan"))
                .andExpect(jsonPath("$.region.provinceName.fr").value("Saskatchewan"))
                .andExpect(jsonPath("$.region.provinceIn.fr").value("en Saskatchewan"))
                .andExpect(jsonPath("$.region.provinceOf.fr").value("de la Saskatchewan"))
                .andExpect(jsonPath("$.region.timeZone").value("America/Regina"))
                .andExpect(jsonPath("$.region.privacyLaw").value("pipeda"));
    }

    @Test
    void holidaysAreSaskatchewans_andTheMessageNamesTheProvince() throws Exception {
        mvc.perform(get(path("/availability/time-off")).with(TestJwt.member(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.province.en").value("Saskatchewan"))
                .andExpect(jsonPath("$.holidays[*].key")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("boxing_day"))));
        var next = LocalDate.now(REGINA).getYear() + 1;
        // Boxing Day is Alberta's (and Ontario's), not Saskatchewan's
        mvc.perform(put(path("/availability/holidays/" + LocalDate.of(next, 12, 26)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"open\":true}")
                        .with(TestJwt.member(owner)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(
                        jsonPath("$.errors[0].message").value("This day is not a statutory holiday in Saskatchewan."));
        var saskatchewanDay = LocalDate.of(next, 8, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY));
        mvc.perform(put(path("/availability/holidays/" + saskatchewanDay))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"open\":true}")
                        .with(TestJwt.member(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("saskatchewan_day"))
                .andExpect(jsonPath("$.name.en").value("Saskatchewan Day"));
    }

    @Test
    void hoursAndSlotsAreSaskatoonLocalTime() throws Exception {
        var monday = LocalDate.now(REGINA).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        mvc.perform(put(path("/availability/hours"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"effectiveFrom":"%s","members":[{"memberUserId":"%s","days":{"mon":[["09:00","12:00"]]}}]}
                                """.formatted(LocalDate.now(REGINA), owner))
                        .with(TestJwt.member(owner)))
                .andExpect(status().isOk());
        // a Monday that's a statutory holiday (Thanksgiving, Labour Day, …) is closed: take the first open one
        var day = slots.days(merchantId, 60, monday, 1, null).getFirst();
        for (int week = 1; week < 4 && day.slots().isEmpty(); week++) {
            monday = monday.plusWeeks(1);
            day = slots.days(merchantId, 60, monday, 1, null).getFirst();
        }
        assertThat(day.slots()).isNotEmpty();
        assertThat(day.slots().getFirst().startsAt())
                .isEqualTo(monday.atTime(LocalTime.of(9, 0)).atZone(REGINA).toInstant());
    }

    @Test
    void serviceZonesAreTheMarkets() throws Exception {
        mvc.perform(get(path("/availability/rules")).with(TestJwt.member(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zones").value(org.hamcrest.Matchers.contains("Nutana", "Riversdale")))
                .andExpect(jsonPath("$.serviceAreas").value(org.hamcrest.Matchers.contains("Nutana")));
        mvc.perform(put(path("/availability/rules"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"intervalMin":30,"bufferMin":20,"minNoticeMin":60,"horizonDays":14,"maxJobsPerDay":5,
                                 "acceptMode":"instant","rescheduleFreeMin":180,"lateCancelFeeCents":0,
                                 "serviceAreas":["Beltline"]}
                                """)
                        .with(TestJwt.member(owner)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("serviceAreas"));
    }

    @Test
    void quotesAreTaxedAtSaskatchewansRate() throws Exception {
        var request = new OperationsFixtures(jdbc).quoteRequest(merchantId, data.user("Customer"));
        mvc.perform(post("/api/v1/merchants/{m}/quote-requests/{r}/quotes", merchantId, request)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(QUOTE)
                        .with(TestJwt.member(owner)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taxBps").value(1100));
    }

    @Test
    void pooledRunsAreAtSaskatoonsLocalTimes() {
        assertThat(runs.market("saskatoon")).hasValue("Saskatoon");
        assertThat(runs.zone("Saskatoon")).isEqualTo(REGINA);
        // the configured runs (evening 18:00, morning 08:00) at Saskatoon's local times
        var scheduled = runs.upcoming("Saskatoon", Instant.now()).stream()
                .filter(r -> !r.slot().equals("other"))
                .toList();
        assertThat(scheduled).isNotEmpty();
        assertThat(scheduled)
                .allSatisfy(r -> assertThat(r.startsAt().atZone(REGINA).toLocalTime())
                        .isEqualTo(r.slot().equals("evening") ? LocalTime.of(18, 0) : LocalTime.of(8, 0)));
    }

    @Test
    void onboardingAKitchen_routesRegistriesByProvinceAndCity() throws Exception {
        new CategorySeeder(dataSource).seed();
        var user = data.user("Kitchen Owner");
        var created = mvc.perform(post("/api/v1/merchants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"kitchen\",\"province\":\"SK\"}")
                        .with(TestJwt.member(user)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String kitchen = JsonPath.read(created, "$.merchantId");
        mvc.perform(put("/api/v1/merchants/{id}/onboarding/business", kitchen)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Prairie Pierogi","legalName":"Olena Koval","structure":"sole",
                                 "legalDetails":{"owner_legal_name":"Olena Koval","trade_name":"Prairie Pierogi",
                                   "trade_name_registration":"TN-2004-118840","sin_collected_by_stripe":true,
                                   "address":"210 20th St W, Saskatoon SK"},
                                 "profile":{"cityLicenceNumber":"BL 22-118840"},
                                 "categoryIds":["food.format.restaurant-dine-in-and-takeout"]}""")
                        .with(TestJwt.member(user)))
                .andExpect(status().isOk());
        var onboarding = mvc.perform(
                        get("/api/v1/merchants/{id}/onboarding", kitchen).with(TestJwt.member(user)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        java.util.List<String> ids = JsonPath.read(onboarding, "$.checklist[?(@.key == 'registry')].id");
        mvc.perform(post("/api/v1/merchants/{id}/verifications/{v}/complete", kitchen, ids.getFirst())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(TestJwt.member(user)))
                .andExpect(status().isOk());
        assertThat(places.of(kitchen).city()).isEqualTo("Saskatoon");
        // the trade name goes to an agent (no Saskatchewan registry adapter); Saskatoon has no licence source
        assertThat(jdbc.sql("select source from merchants.registry_checks where verification_id = ?")
                        .params(ids.getFirst())
                        .query(String.class)
                        .list())
                .containsExactly("manual");
    }

    @Test
    void aClosedProvinceIsRefusedByName() throws Exception {
        mvc.perform(post("/api/v1/merchants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"provider\",\"province\":\"YT\"}")
                        .with(TestJwt.member(data.user("Far North"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("province"))
                .andExpect(jsonPath("$.errors[0].message").value("Northline isn't open in Yukon yet."));
    }
}
