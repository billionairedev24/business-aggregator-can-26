package ca.northline.merchants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.merchants.api.PilotCohort;
import ca.northline.merchants.api.ShopDirectory;
import ca.northline.merchants.application.DevIdentityOutcomes;
import ca.northline.region.api.Regions;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import ca.northline.tools.CategorySeeder;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * S-120: pilot onboarding — invites (signed, expiring, emailed, single use) that pre-fill the business type and market
 * and mark the business as a pilot; the console's pipeline board, derived from the business's own state; blockers,
 * owners, notes and CSV; kitchen visits that gate a kitchen's approval where the region requires them; the pre-launch
 * search hiding lifted when the market opens; and the role gates (merchant success onboards, trust &amp; safety reads).
 * Also the S-117 finding: a business approved without an address naming a market still gets one.
 */
class PilotOnboardingApiTest extends IntegrationTest {

    static final String BASE = "/api/v1/console/pilot";
    static final String MARKET = "mkt-s120-pilotville";
    static final String KITCHEN_CATEGORY = "food.format.restaurant-dine-in-and-takeout";
    private static volatile boolean seeded;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DevIdentityOutcomes identity;

    @Autowired
    Regions regions;

    @Autowired
    PilotCohort cohort;

    @Autowired
    ShopDirectory shops;

    OnboardingFlow flow;
    String staff;
    String agent;

    @BeforeEach
    void setUp() {
        if (!seeded) {
            new CategorySeeder(dataSource).seed();
            seeded = true;
        }
        // a market at the pilot stage whose kitchens are visited before approval (region data, no code)
        jdbc.sql("""
                        insert into region.regions (id, kind, parent_id, province, city, name_i18n, stage, center,
                                                    radius_km, languages, sort, kitchen_visit)
                        values (:id, 'market', 'prov-ab', 'AB', 'Pilotville', '{"en":"Pilotville","fr":"Pilotville"}',
                                'pilot', 'SRID=4326;POINT(-113.9 50.9)', 15, '{en,fr}', 90, 'required')
                        on conflict (id) do update set stage = 'pilot', kitchen_visit = 'required'
                        """).param("id", MARKET).update();
        regions.refresh();
        flow = new OnboardingFlow(mvc, dataSource, identity);
        staff = data.user("Mina Success");
        agent = data.user("Dev Kaur");
        jdbc.sql(
                        "insert into identity.platform_roles (user_id, role, granted_at) values (?, 'staff', now()), (?, 'merchant_success', now())")
                .params(staff, staff)
                .update();
    }

    /** The market leaves the served model again, so every other test class sees the region rows as before. */
    @AfterEach
    void closeMarket() {
        jdbc.sql("update region.regions set stage = 'off' where id = ?")
                .params(MARKET)
                .update();
        regions.refresh();
    }

    MockHttpServletRequestBuilder ms(MockHttpServletRequestBuilder request) {
        return request.with(TestJwt.staff(staff, StaffRole.MERCHANT_SUCCESS));
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** Invites a business → {pilotId, link}. */
    Map<String, String> invite(String type, String label, String email) throws Exception {
        var body = mvc.perform(ms(json(post(BASE + "/invites"), """
                        {"marketId":"%s","businessType":"%s","label":"%s","email":"%s","language":"en","ownerId":"%s"}
                        """.formatted(MARKET, type, label, email, staff))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Map.of("pilotId", JsonPath.read(body, "$.detail.row.id"), "link", JsonPath.read(body, "$.link"));
    }

    static String token(String link) {
        return link.substring(link.lastIndexOf('/') + 1);
    }

    /** The Account step with the invite's token → the new business's id. */
    String accept(String owner, String type, String token) throws Exception {
        var body = mvc.perform(json(post("/api/v1/merchants"), """
                                {"type":"%s","province":"AB","pilotInvite":"%s"}""".formatted(type, token))
                        .with(TestJwt.member(owner)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.merchantId");
    }

    String detail(String pilotId) throws Exception {
        return mvc.perform(ms(get(BASE + "/{id}", pilotId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    Map<String, Object> row(String pilotId) throws Exception {
        return JsonPath.read(detail(pilotId), "$.row");
    }

    @Test
    void anInvite_isEmailed_preFillsTypeAndMarket_andMakesThePilotBusiness() throws Exception {
        var email = "pilot-" + staff.toLowerCase(java.util.Locale.ROOT) + "@example.test";
        var invited = invite("seller", "Bow River Bakery", email);
        var link = invited.get("link");
        assertThat(link).contains("/pilot/");

        await().atMost(Duration.ofSeconds(10)).until(() -> !emails.to(email).isEmpty());
        assertThat(emails.to(email)).singleElement().satisfies(mail -> {
            assertThat(mail.subject()).isEqualTo("You’re invited to the Northline pilot in Pilotville");
            assertThat(mail.text()).contains(link, "Bow River Bakery", "Mina Success", "as a shop");
            assertThat(mail.tag()).isEqualTo("pilot-invitation");
        });

        // the board: invited, waiting for the business to accept
        var board = mvc.perform(ms(get(BASE + "?market=" + MARKET)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<Map<String, Object>> rows =
                JsonPath.read(board, "$.items[?(@.id == '%s')]".formatted(invited.get("pilotId")));
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r).containsEntry("stage", "invited").containsEntry("inviteState", "pending");
            assertThat(r).containsEntry("ownerName", "Mina Success");
            assertThat((Map<String, Object>) r.get("next"))
                    .containsEntry("key", "account_created")
                    .containsEntry("action", "accept_invite")
                    .containsEntry("owner", "business");
        });
        assertThat((List<?>) JsonPath.read(board, "$.markets[?(@.id == '%s')]".formatted(MARKET)))
                .hasSize(1);

        // the Studio previews it (signed-in person), then the Account step accepts it
        var owner = data.user("Ada Baker");
        mvc.perform(get("/api/v1/pilot-invites/{t}", token(link)).with(TestJwt.member(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessType").value("seller"))
                .andExpect(jsonPath("$.marketId").value(MARKET))
                .andExpect(jsonPath("$.city").value("Pilotville"))
                .andExpect(jsonPath("$.province").value("AB"))
                .andExpect(jsonPath("$.state").value("pending"));
        mvc.perform(json(
                                post("/api/v1/merchants"),
                                "{\"type\":\"kitchen\",\"province\":\"AB\",\"pilotInvite\":\"%s\"}"
                                        .formatted(token(link)))
                        .with(TestJwt.member(owner)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("type"))
                .andExpect(jsonPath("$.errors[0].message").value("This invite is for another kind of business."));
        var merchantId = accept(owner, "seller", token(link));

        var r = row(invited.get("pilotId"));
        assertThat(r).containsEntry("merchantId", merchantId).containsEntry("stage", "account_created");
        assertThat((Map<String, Object>) r.get("next")).containsEntry("action", "complete_details");
        // hidden from search until the pilot market opens
        assertThat(searchHidden(merchantId)).isEqualTo("pilot");
        // single use
        mvc.perform(json(
                                post("/api/v1/merchants"),
                                "{\"type\":\"seller\",\"province\":\"AB\",\"pilotInvite\":\"%s\"}"
                                        .formatted(token(link)))
                        .with(TestJwt.member(data.user("Someone Else"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("pilot_invite_used"));
        assertThat(jdbc.sql("select count(*) from developer.audit_log where target_id = ? and action like 'pilot.%'")
                        .params(invited.get("pilotId"))
                        .query(Long.class)
                        .single())
                .isGreaterThanOrEqualTo(3); // created, invited, invite_accepted

        // CSV: one line per business, codes
        mvc.perform(ms(get(BASE + "/export?market=" + MARKET)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content()
                        .string(org.hamcrest.Matchers.containsString(
                                invited.get("pilotId") + "," + MARKET + ",Bow River Bakery,seller," + merchantId)));
    }

    @Test
    void aNewLink_revokesTheLastOne_andAnExpiredOrWithdrawnLinkIsRefused() throws Exception {
        var first = invite("provider", "Chinook Plumbing", "chinook@example.test");
        var body = mvc.perform(ms(json(post(BASE + "/{id}/invites", first.get("pilotId")), "{\"language\":\"fr\"}")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String second = JsonPath.read(body, "$.link");
        assertThat(second).isNotEqualTo(first.get("link"));
        mvc.perform(json(
                                post("/api/v1/merchants"),
                                "{\"type\":\"provider\",\"province\":\"AB\",\"pilotInvite\":\"%s\"}"
                                        .formatted(token(first.get("link"))))
                        .with(TestJwt.member(data.user("Late Owner"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("pilot_invite_revoked"));
        jdbc.sql(
                        "update merchants.pilot_invites set expires_at = now() - interval '1 minute' where pilot_id = ? and revoked_at is null")
                .params(first.get("pilotId"))
                .update();
        mvc.perform(json(
                                post("/api/v1/merchants"),
                                "{\"type\":\"provider\",\"province\":\"AB\",\"pilotInvite\":\"%s\"}"
                                        .formatted(token(second)))
                        .with(TestJwt.member(data.user("Late Owner"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("pilot_invite_expired"));
        var r = row(first.get("pilotId"));
        assertThat(r).containsEntry("inviteState", "expired");
        assertThat((Map<String, Object>) r.get("next")).containsEntry("action", "resend_invite");
    }

    @Test
    void validationMessages() throws Exception {
        mvc.perform(ms(json(post(BASE + "/invites"), """
                        {"marketId":"mkt-nowhere","businessType":"seller","label":"X","email":"x@example.test"}""")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].message").value(PilotCohort.MARKET_REQUIRED));
        mvc.perform(ms(json(post(BASE + "/invites"), """
                        {"marketId":"%s","businessType":"bakery","label":"X","email":"x@example.test"}""".formatted(MARKET))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].message").value(PilotCohort.TYPE_REQUIRED));
        mvc.perform(ms(json(post(BASE + "/invites"), """
                        {"marketId":"%s","businessType":"seller","label":"X","email":"not-an-email"}""".formatted(MARKET))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].message").value(PilotCohort.EMAIL_FORMAT));
        mvc.perform(ms(json(post(BASE + "/invites"), """
                        {"marketId":"%s","businessType":"seller","label":" ","email":"x@example.test"}""".formatted(MARKET))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].message").value(PilotCohort.LABEL_REQUIRED));
        mvc.perform(ms(json(post(BASE + "/invites"), """
                        {"marketId":"%s","businessType":"seller","label":"X","email":"x@example.test","ownerId":"%s"}""".formatted(MARKET, agent))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].message").value(PilotCohort.OWNER_NOT_STAFF));
        var pilot =
                invite("seller", "Kensington Candles", "candles@example.test").get("pilotId");
        mvc.perform(ms(json(put(BASE + "/{id}/blocker", pilot), "{\"text\":\"Waiting for the GST number\"}")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].message").value(PilotCohort.BLOCKER_OWNER_REQUIRED));
        mvc.perform(ms(json(post(BASE + "/{id}/notes", pilot), "{\"body\":\"\"}")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].message").value(PilotCohort.NOTE_REQUIRED));
        mvc.perform(ms(json(post(BASE + "/{id}/kitchen-visits", pilot), "{\"at\":\"2030-01-01T10:00:00Z\"}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("pilot_not_started"));
    }

    @Test
    void blockersOwnersAndNotes_showOnTheBoard() throws Exception {
        var pilot =
                invite("provider", "Nose Hill Movers", "movers@example.test").get("pilotId");
        mvc.perform(ms(json(
                        put(BASE + "/{id}/blocker", pilot),
                        "{\"text\":\"Insurance renewal in May\",\"owner\":\"business\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.row.blocked").value(true))
                .andExpect(jsonPath("$.row.blocker").value("Insurance renewal in May"))
                .andExpect(jsonPath("$.row.blockerOwner").value("business"));
        mvc.perform(ms(json(post(BASE + "/{id}/notes", pilot), "{\"body\":\"Called the owner; docs by Friday.\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes[0].body").value("Called the owner; docs by Friday."))
                .andExpect(jsonPath("$.notes[0].authorName").value("Mina Success"));
        mvc.perform(ms(json(put(BASE + "/{id}/owner", pilot), "{}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.row.ownerId").doesNotExist());
        mvc.perform(ms(json(put(BASE + "/{id}/blocker", pilot), "{}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.row.blocked").value(false));
    }

    @Test
    void roles_merchantSuccessOnboards_trustAndSafetyReads_othersAreRefused() throws Exception {
        mvc.perform(get(BASE).with(TestJwt.staff(agent, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isOk());
        mvc.perform(json(post(BASE + "/invites"), """
                                {"marketId":"%s","businessType":"seller","label":"X","email":"x@example.test"}""".formatted(MARKET))
                        .with(TestJwt.staff(agent, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient_role"));
        mvc.perform(get(BASE).with(TestJwt.staff(agent, StaffRole.DISPATCH)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient_role"));
        mvc.perform(get(BASE).with(TestJwt.staffWithoutMfa(staff, StaffRole.MERCHANT_SUCCESS)))
                .andExpect(status().isForbidden());
        mvc.perform(get(BASE).with(TestJwt.member(staff))).andExpect(status().isForbidden());
        mvc.perform(get(BASE).with(TestJwt.staff(agent, StaffRole.ADMIN))).andExpect(status().isOk());
    }

    @Test
    void aKitchenInAMarketThatRequiresVisits_isApprovedOnlyAfterAPassedVisit() throws Exception {
        var invited = invite("kitchen", "Pilotville Dumplings", "dumplings@example.test");
        var pilot = invited.get("pilotId");
        var owner = data.user("Lan Nguyen");
        var kitchen = accept(owner, "kitchen", token(invited.get("link")));
        flow.business(kitchen, owner, """
                        {"displayName":"Pilotville Dumplings","legalName":"Lan Nguyen","structure":"sole",
                         "legalDetails":{"owner_legal_name":"Lan Nguyen","sin_collected_by_stripe":true,
                           "address":"4 Main St, Pilotville AB"},
                         "categoryIds":["%s"]}""".formatted(KITCHEN_CATEGORY)).andExpect(status().isOk());
        flow.completeAll(kitchen, owner);
        flow.submit(kitchen, owner).andExpect(status().isOk());
        decideRegistryReviews(kitchen);
        List<Map<String, Object>> visitStep =
                JsonPath.read(detail(pilot), "$.row.checklist[?(@.key == 'kitchen_visit')]");
        assertThat(visitStep)
                .singleElement()
                .satisfies(s -> assertThat(s).containsEntry("state", "todo").containsEntry("action", "schedule_visit"));

        // approving before the visit passed is refused
        approve(kitchen)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("kitchen_visit_required"));

        var at = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        var scheduled = mvc.perform(ms(json(
                        post(BASE + "/{id}/kitchen-visits", pilot),
                        "{\"at\":\"%s\",\"inspectorName\":\"R. Okafor (food safety consultant)\"}".formatted(at))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visits[0].status").value("scheduled"))
                .andExpect(jsonPath("$.row.checklist[5].state").value("waiting"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String visit = JsonPath.read(scheduled, "$.visits[0].id");
        List<String> items = JsonPath.read(scheduled, "$.visitItems");

        var photo =
                new MockMultipartFile("file", "walk-in.jpg", "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2});
        var withPhoto = mvc.perform(multipart(BASE + "/{id}/kitchen-visits/{v}/photos", pilot, visit)
                        .file(photo)
                        .with(TestJwt.staff(staff, StaffRole.MERCHANT_SUCCESS)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String photoId = JsonPath.read(withPhoto, "$.visits[0].photoIds[0]");
        mvc.perform(ms(get(BASE + "/{id}/kitchen-visits/{v}/photos/{p}", pilot, visit, photoId)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"));
        mvc.perform(multipart(BASE + "/{id}/kitchen-visits/{v}/photos", pilot, visit)
                        .file(new MockMultipartFile("file", "x.pdf", "application/pdf", new byte[] {1}))
                        .with(TestJwt.staff(staff, StaffRole.MERCHANT_SUCCESS)))
                .andExpect(status().isUnprocessableEntity());

        // an incomplete checklist, then a failed item without a note, are refused
        mvc.perform(ms(json(
                        post(BASE + "/{id}/kitchen-visits/{v}/outcome", pilot, visit),
                        "{\"outcome\":\"passed\",\"checklist\":{}}")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].message").value("Mark every item on the checklist."));
        var marks = new StringBuilder("{");
        for (var i = 0; i < items.size(); i++) {
            marks.append(i == 0 ? "" : ",").append('"').append(items.get(i)).append("\":\"pass\"");
        }
        marks.append('}');
        mvc.perform(ms(json(
                        post(BASE + "/{id}/kitchen-visits/{v}/outcome", pilot, visit),
                        "{\"outcome\":\"passed\",\"checklist\":%s}".formatted(marks))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visits[0].status").value("passed"))
                .andExpect(jsonPath("$.row.checklist[5].state").value("done"));

        approve(kitchen).andExpect(status().isOk());
        var r = row(pilot);
        assertThat(r).containsEntry("city", "Pilotville");
        List<Map<String, Object>> live = JsonPath.read(detail(pilot), "$.row.checklist[?(@.key == 'approved')]");
        assertThat(live).singleElement().satisfies(s -> assertThat(s).containsEntry("state", "done"));
        assertThat(jdbc.sql(
                                "select action from developer.audit_log where merchant_id = ? and action like 'kitchen_visit.%' order by at")
                        .params(kitchen)
                        .query(String.class)
                        .list())
                .containsExactly("kitchen_visit.scheduled", "kitchen_visit.photo_added", "kitchen_visit.passed");

        // the market opens: its pilot businesses are shown in search
        assertThat(searchHidden(kitchen)).isEqualTo("pilot");
        assertThat(cohort.marketLaunched(MARKET, new PilotCohort.Actor(staff, "admin")))
                .isGreaterThanOrEqualTo(1);
        assertThat(searchHidden(kitchen)).isNull();
    }

    @Test
    void aFailedVisit_sendsTheOwnerBackToBookAgain() throws Exception {
        var invited = invite("kitchen", "Pilotville Pho", "pho@example.test");
        var owner = data.user("Minh Tran");
        var kitchen = accept(owner, "kitchen", token(invited.get("link")));
        flow.business(kitchen, owner, OnboardingFlow.soleBusiness("Pilotville Pho", KITCHEN_CATEGORY))
                .andExpect(status().isOk());
        var at = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        var body = mvc.perform(ms(json(
                        post(BASE + "/{id}/kitchen-visits", invited.get("pilotId")),
                        "{\"at\":\"%s\",\"inspectorId\":\"%s\"}".formatted(at, staff))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String visit = JsonPath.read(body, "$.visits[0].id");
        List<String> items = JsonPath.read(body, "$.visitItems");
        var marks = new StringBuilder("{");
        for (var i = 0; i < items.size(); i++) {
            marks.append(i == 0 ? "" : ",")
                    .append('"')
                    .append(items.get(i))
                    .append("\":\"")
                    .append(i == 0 ? "fail" : "pass")
                    .append('"');
        }
        marks.append('}');
        mvc.perform(ms(json(
                        post(BASE + "/{id}/kitchen-visits/{v}/outcome", invited.get("pilotId"), visit),
                        "{\"outcome\":\"failed\",\"checklist\":%s}".formatted(marks))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].message").value("Say what has to be fixed before the next visit."));
        mvc.perform(ms(json(
                        post(BASE + "/{id}/kitchen-visits/{v}/outcome", invited.get("pilotId"), visit),
                        "{\"outcome\":\"failed\",\"checklist\":%s,\"note\":\"No soap at the hand sink.\"}"
                                .formatted(marks))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.row.checklist[5].state").value("blocked"))
                .andExpect(jsonPath("$.row.checklist[5].action").value("visit_failed"));
        assertThat(jdbc.sql(
                                "select status from merchants.verifications where merchant_id = ? and check_key = 'site_visit'")
                        .params(kitchen)
                        .query(String.class)
                        .single())
                .isEqualTo("rejected");
    }

    /** S-117: a business whose addresses name no market used to be approved without one, and the shop listed none. */
    @Test
    void aBusinessApprovedWithoutAMarketCity_getsItsProvincesDefaultMarket() throws Exception {
        var owner = data.user("Wren Potter");
        var id = flow.start(owner, "seller");
        flow.business(id, owner, """
                        {"displayName":"Ridge Pottery","legalName":"Wren Potter","structure":"sole",
                         "legalDetails":{"owner_legal_name":"Wren Potter","sin_collected_by_stripe":true,
                           "address":"RR 2, Site 4, Box 9"},
                         "categoryIds":["shop.food-and-grocery.bakery"]}""").andExpect(status().isOk());
        assertThat(jdbc.sql("select city from merchants.merchants where id = ?")
                        .params(id)
                        .query(String.class)
                        .optional())
                .isEmpty();
        flow.completeAll(id, owner);
        flow.submit(id, owner).andExpect(status().isOk());
        approve(id).andExpect(status().isOk());

        var city = jdbc.sql("select city from merchants.merchants where id = ?")
                .params(id)
                .query(String.class)
                .single();
        var expected = regions.markets().stream()
                .filter(m -> m.province().equals("AB") && m.live())
                .findFirst()
                .orElseThrow()
                .city();
        assertThat(city).isEqualTo(expected);
        assertThat(shops.shopsIn(city)).anyMatch(s -> s.merchantId().equals(id));
    }

    org.springframework.test.web.servlet.ResultActions approve(String merchantId) throws Exception {
        return mvc.perform(json(
                        post("/api/v1/console/verification/applications/{id}/decision", merchantId),
                        "{\"decision\":\"approve\"}")
                .with(TestJwt.staff(agent, StaffRole.TRUST_SAFETY)));
    }

    /** Registry lookups an agent has to confirm (no registry adapter for the test market) — approve them. */
    void decideRegistryReviews(String merchantId) throws Exception {
        for (var review : jdbc.sql(
                        "select id from merchants.registry_checks where merchant_id = ? and review_state = 'open'")
                .params(merchantId)
                .query(String.class)
                .list()) {
            mvc.perform(json(
                                    post("/api/v1/console/registry-reviews/{id}/decision", review),
                                    "{\"decision\":\"approve\"}")
                            .with(TestJwt.staff(agent, StaffRole.TRUST_SAFETY)))
                    .andExpect(status().isOk());
        }
    }

    String searchHidden(String merchantId) {
        return jdbc.sql("select search_hidden_cause from merchants.merchants where id = ?")
                .params(merchantId)
                .query(String.class)
                .optional()
                .orElse(null);
    }
}
