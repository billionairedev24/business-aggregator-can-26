package ca.northline.restricted;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.fulfilment.api.DeliveryRefused;
import ca.northline.merchants.api.RestrictedLicenceChanged;
import ca.northline.merchants.application.RestrictedLicenceUseCases.LicenceJobs;
import ca.northline.restricted.application.DevAgeOutcomes;
import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.MovableClock;
import ca.northline.support.ShopFixtures.Listing;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import net.minidev.json.JSONArray;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Age-restricted purchases end to end (owner decision 2026-10-04) in market "Liquorville", a business in Alberta: the
 * licence (Studio → console vetting queue → expiry), the restricted listing's visibility, checkout's age check with the
 * fake identity provider, and the courier's ID check at the door — handed over, or refused and returned with the
 * refund rule. The clock is moved to noon in the platform zone so the province's sale hours (V340) are open.
 */
@Import(MovableClock.Config.class)
@RecordApplicationEvents
class AgeRestrictedPurchaseApiTest extends IntegrationTest {

    static final String MARKET = "Liquorville";
    static final String ALCOHOL = "shop.restricted.alcohol";
    static final byte[] PDF = "%PDF-1.4 licence".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MovableClock clock;

    @Autowired
    ApplicationEvents events;

    @Autowired
    DevAgeOutcomes fakeIdentity;

    @Autowired
    LicenceJobs licenceJobs;

    String shop;
    String owner;
    String staff;
    Listing wine;
    Listing bread;

    @BeforeEach
    void market() {
        clock.reset();
        // noon in the platform zone: inside every configured sale window
        var zone = ZoneId.of("America/Edmonton");
        var now = ZonedDateTime.ofInstant(clock.instant(), zone);
        var noon = now.toLocalDate().plusDays(1).atTime(LocalTime.NOON).atZone(zone);
        clock.advance(Duration.between(now, noon));
        jdbc.sql("update fulfilment.couriers set active = false where market = ?")
                .params(MARKET)
                .update();
        jdbc.sql("update fulfilment.runs set state = 'done' where market = ? and state <> 'done'")
                .params(MARKET)
                .update();
        shopFixtures.categories();
        shop = shopFixtures.shop(MARKET, "Prairie Cellars", "trusted");
        jdbc.sql("update merchants.merchants set province = 'AB' where id = ?")
                .params(shop)
                .update();
        owner = data.user("Olive Owner");
        jdbc.sql("update identity.users set email = ? where id = ?")
                .params("olive+" + owner.toLowerCase(java.util.Locale.ROOT) + "@example.invalid", owner)
                .update();
        data.member(shop, owner, MerchantRole.OWNER);
        wine = shopFixtures.listing(shop, ALCOHOL, "Okanagan pinot noir", 2800, 20);
        bread = shopFixtures.listing(shop, "shop.food-and-grocery.bakery", "Seed loaf", 650, 20);
        staff = data.user("Tess Trust");
    }

    @AfterEach
    void time() {
        clock.reset();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    ResultActions submitLicence(String user, String ageClass, String number, String expiresOn) throws Exception {
        return mvc.perform(multipart("/api/v1/merchants/{m}/restricted-licences", shop)
                .file(new MockMultipartFile("file", "licence.pdf", "application/pdf", PDF))
                .param("ageClass", ageClass)
                .param("licenceNumber", number)
                .param("expiresOn", expiresOn)
                .with(TestJwt.member(user)));
    }

    /** Submitted by the owner and approved by trust &amp; safety; returns the licence id. */
    String licensed() throws Exception {
        var submitted = body(submitLicence(
                        owner,
                        "alcohol",
                        "RLS-778812",
                        LocalDate.now().plusYears(1).toString())
                .andExpect(status().isOk()));
        String id = JsonPath.read(submitted, "$.id");
        mvc.perform(json(post("/api/v1/console/vetting/licences/{id}/decision", id), "{\"decision\":\"approve\"}")
                        .with(TestJwt.staff(staff, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(10))
                .until(() -> offerStatus(wine.offerId()).equals("live"));
        return id;
    }

    String offerStatus(String offerId) {
        return jdbc.sql("select status from catalogue.offers where id = ?")
                .params(offerId)
                .query(String.class)
                .single();
    }

    ResultActions addToCart(String customer, Listing listing) throws Exception {
        return mvc.perform(
                json(post("/api/v1/cart/items"), "{\"offerId\":\"%s\",\"qty\":1}".formatted(listing.offerId()))
                        .with(TestJwt.customerWithMfa(customer)));
    }

    static String checkout(String province) {
        return """
                {"kind":"direct","address":{"street":"10 Main St","city":"%s","province":"%s","postal":"T2T 0B8"},
                 "substitution":"refund"}""".formatted(MARKET, province);
    }

    ResultActions start(String customer) throws Exception {
        return mvc.perform(json(post("/api/v1/me/checkouts"), checkout("AB"))
                .with(TestJwt.customerWithMfa(customer))
                .header("Idempotency-Key", Ids.next()));
    }

    /** The customer's age check, finished with the fake provider's outcome. */
    void verifyAge(String customer, String outcome) throws Exception {
        var started = body(mvc.perform(json(post("/api/v1/me/age-verification"), "{\"returnTo\":\"web\"}")
                        .with(TestJwt.customerWithMfa(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status.state").value("pending")));
        String url = JsonPath.read(started, "$.url");
        assertThat(fakeIdentity.finish(url.substring(url.lastIndexOf('/') + 1), outcome))
                .contains("http://localhost:3000/cart?age=done");
    }

    String placeWine(String customer) throws Exception {
        addToCart(customer, wine).andExpect(status().isCreated());
        var started = body(start(customer).andExpect(status().isCreated()));
        var placed =
                body(mvc.perform(post("/api/v1/me/checkouts/{id}/place", JsonPath.<String>read(started, "$.checkoutId"))
                                .with(TestJwt.customerWithMfa(customer))
                                .header("Idempotency-Key", Ids.next()))
                        .andExpect(status().isCreated()));
        return JsonPath.read(placed, "$.orderId");
    }

    String courierOnShift(String name) throws Exception {
        var user = data.user(name);
        var dispatcher = TestJwt.staff(staff, StaffRole.DISPATCH);
        var courier = body(mvc.perform(json(
                                post("/api/v1/console/fulfilment/couriers"),
                                "{\"userId\":\"%s\",\"market\":\"%s\",\"vehicle\":\"car\"}".formatted(user, MARKET))
                        .with(dispatcher))
                .andExpect(status().isCreated()));
        var now = clock.instant();
        var shift = body(mvc.perform(json(
                                post(
                                        "/api/v1/console/fulfilment/couriers/{id}/shifts",
                                        JsonPath.<String>read(courier, "$.id")),
                                "{\"startsAt\":\"%s\",\"endsAt\":\"%s\"}"
                                        .formatted(now.minus(Duration.ofMinutes(5)), now.plus(Duration.ofHours(6))))
                        .with(dispatcher))
                .andExpect(status().isCreated()));
        mvc.perform(post("/api/v1/courier/shifts/{id}/start", JsonPath.<String>read(shift, "$.id"))
                        .with(TestJwt.courier(user)))
                .andExpect(status().isOk());
        return user;
    }

    /** The order packed, planned on a direct run for the courier and picked up; returns the run's JSON. */
    String pickedUp(String orderId, String courier) throws Exception {
        await().atMost(Duration.ofSeconds(10))
                .until(() -> jdbc.sql("select count(*) from fulfilment.deliveries where order_id = ?")
                                .params(orderId)
                                .query(Long.class)
                                .single()
                        == 1);
        mvc.perform(post("/api/v1/merchants/{m}/orders/{o}/pack", shop, orderId).with(TestJwt.member(owner)))
                .andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(10))
                .until(() -> jdbc.sql("""
                                        select count(*) from fulfilment.delivery_pickups
                                         where order_id = ? and packed_at is not null""").params(orderId).query(Long.class).single() == 1);
        mvc.perform(json(post("/api/v1/console/fulfilment/plan"), "{\"market\":\"%s\"}".formatted(MARKET))
                        .with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                .andExpect(status().isOk());
        var me = TestJwt.courier(courier);
        var run = body(mvc.perform(get("/api/v1/courier/run").with(me)).andExpect(status().isOk()));
        for (var stop : (JSONArray) JsonPath.read(run, "$.stops[?(@.kind == 'pickup')].id")) {
            mvc.perform(json(post("/api/v1/courier/stops/{id}/pickup", stop), "{\"scanOk\":true}")
                            .with(me))
                    .andExpect(status().isOk());
        }
        return body(mvc.perform(get("/api/v1/courier/run").with(me)).andExpect(status().isOk()));
    }

    static String stop(String run, String kind) {
        return ((JSONArray) JsonPath.read(run, "$.stops[?(@.kind == '%s')].id".formatted(kind)))
                .getFirst()
                .toString();
    }

    // ── licences ────────────────────────────────────────────────────────────────────────────────────────────

    @Nested
    class Licences {

        @Test
        void aRestrictedListingIsSoldOnlyWhileTheBusinessHoldsAnApprovedLicence() throws Exception {
            var customer = data.user("Cam Customer");
            // no licence: the listing can't be bought, and the Studio shows no class licensed
            addToCart(customer, wine).andExpect(status().isUnprocessableContent());
            mvc.perform(get("/api/v1/merchants/{m}/restricted-licences", shop).with(TestJwt.member(owner)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.licensed.length()").value(0));

            var submitted = body(submitLicence(
                            owner,
                            "alcohol",
                            "RLS-778812",
                            LocalDate.now().plusYears(1).toString())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("pending"))
                    .andExpect(jsonPath("$.province").value("AB")));
            String id = JsonPath.read(submitted, "$.id");
            var queue = body(mvc.perform(
                            get("/api/v1/console/vetting/licences").with(TestJwt.staff(staff, StaffRole.TRUST_SAFETY)))
                    .andExpect(status().isOk()));
            assertThat(JsonPath.<JSONArray>read(queue, "$.items[?(@.licence.id == '%s')].businessName".formatted(id)))
                    .containsExactly("Prairie Cellars");
            mvc.perform(get("/api/v1/console/vetting/licences/{id}/document", id)
                            .with(TestJwt.staff(staff, StaffRole.TRUST_SAFETY)))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                            .string("X-Content-Type-Options", "nosniff"));

            mvc.perform(json(post("/api/v1/console/vetting/licences/{id}/decision", id), "{\"decision\":\"approve\"}")
                            .with(TestJwt.staff(staff, StaffRole.TRUST_SAFETY)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.licence.status").value("approved"));
            assertThat(events.stream(RestrictedLicenceChanged.class))
                    .anyMatch(e -> e.aggregateId().equals(shop) && e.licensed());
            assertThat(jdbc.sql("""
                            select count(*) from developer.audit_log
                             where target_id = ? and action = 'merchants.restricted_licence_approved'""").params(id).query(Long.class).single()).isEqualTo(1);
            // the owners are told
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> !emails.to("olive+" + owner.toLowerCase(java.util.Locale.ROOT) + "@example.invalid")
                            .isEmpty());
            addToCart(customer, wine).andExpect(status().isCreated());
            // a second decision is a conflict
            mvc.perform(json(post("/api/v1/console/vetting/licences/{id}/decision", id), "{\"decision\":\"approve\"}")
                            .with(TestJwt.staff(staff, StaffRole.TRUST_SAFETY)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("licence_decided"));
        }

        @Test
        void anExpiredLicenceHidesTheListingsUntilARenewalIsApproved() throws Exception {
            var id = licensed();
            jdbc.sql("update merchants.restricted_licences set expires_on = current_date - 2 where id = ?")
                    .params(id)
                    .update();
            assertThat(licenceJobs.expireDue()).isGreaterThanOrEqualTo(1);
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> offerStatus(wine.offerId()).equals("hidden"));
            assertThat(jdbc.sql("select licence_hold from catalogue.offers where id = ?")
                            .params(wine.offerId())
                            .query(Boolean.class)
                            .single())
                    .isTrue();
            // the seller can't publish it again by hand
            mvc.perform(post("/api/v1/merchants/{m}/listings/{l}/publish", shop, wine.offerId())
                            .with(TestJwt.member(owner)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("licence_required"));
            // the bread is not age-restricted: untouched
            assertThat(offerStatus(bread.offerId())).isEqualTo("live");
            licensed(); // a renewal: the held listing comes back
            assertThat(jdbc.sql("select status from merchants.restricted_licences where id = ?")
                            .params(id)
                            .query(String.class)
                            .single())
                    .isEqualTo("expired");
        }

        @Test
        void remindersGoOutOnceBeforeExpiry() throws Exception {
            var id = licensed();
            jdbc.sql("update merchants.restricted_licences set expires_on = current_date + 10 where id = ?")
                    .params(id)
                    .update();
            assertThat(licenceJobs.remindDue()).isGreaterThanOrEqualTo(1);
            assertThat(licenceJobs.remindDue()).isZero();
            assertThat(jdbc.sql("select reminded_at is not null from merchants.restricted_licences where id = ?")
                            .params(id)
                            .query(Boolean.class)
                            .single())
                    .isTrue();
        }

        @Test
        void onlyOwnersSubmitOnlyTrustAndSafetyDecide() throws Exception {
            var tech = data.user("Tom Tech");
            data.member(shop, tech, MerchantRole.TECHNICIAN);
            submitLicence(tech, "alcohol", "RLS-1", "2030-01-01").andExpect(status().isForbidden());
            submitLicence(data.user("Stranger"), "alcohol", "RLS-1", "2030-01-01")
                    .andExpect(status().isForbidden());
            mvc.perform(multipart("/api/v1/merchants/{m}/restricted-licences", shop)
                            .file(new MockMultipartFile("file", "l.pdf", "application/pdf", PDF))
                            .param("ageClass", "alcohol")
                            .param("licenceNumber", "RLS-1")
                            .param("expiresOn", "2030-01-01")
                            .with(TestJwt.memberWithoutMfa(owner)))
                    .andExpect(status().isForbidden());
            var submitted =
                    body(submitLicence(owner, "alcohol", "RLS-9", "2030-01-01").andExpect(status().isOk()));
            String id = JsonPath.read(submitted, "$.id");
            mvc.perform(json(post("/api/v1/console/vetting/licences/{id}/decision", id), "{\"decision\":\"approve\"}")
                            .with(TestJwt.staff(staff, StaffRole.SUPPORT)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/console/vetting/licences").with(TestJwt.staff(staff, StaffRole.FINANCE)))
                    .andExpect(status().isForbidden());
            mvc.perform(json(post("/api/v1/console/vetting/licences/{id}/decision", id), "{\"decision\":\"reject\"}")
                            .with(TestJwt.staff(staff, StaffRole.TRUST_SAFETY)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Choose why the licence is rejected."));
            mvc.perform(json(
                                    post("/api/v1/console/vetting/licences/{id}/decision", id),
                                    "{\"decision\":\"reject\",\"reason\":\"unreadable\",\"note\":\"Blurred.\"}")
                            .with(TestJwt.staff(staff, StaffRole.TRUST_SAFETY)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.licence.status").value("rejected"))
                    .andExpect(jsonPath("$.licence.rejectReason").value("unreadable"));
        }

        @Test
        void validationMessages() throws Exception {
            submitLicence(owner, "beer", "R", "2001-01-01")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[?(@.field == 'ageClass')].message")
                            .value("Choose alcohol, tobacco and vape, or cannabis accessories."))
                    .andExpect(jsonPath("$.errors[?(@.field == 'licenceNumber')].message")
                            .value("Enter the licence or permit number, 2 to 40 characters."))
                    .andExpect(jsonPath("$.errors[?(@.field == 'expiresOn')].message")
                            .value("Enter an expiry date in the future."));
            mvc.perform(multipart("/api/v1/merchants/{m}/restricted-licences", shop)
                            .param("ageClass", "alcohol")
                            .param("licenceNumber", "RLS-1")
                            .param("expiresOn", "2030-01-01")
                            .with(TestJwt.member(owner)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("file"))
                    .andExpect(jsonPath("$.errors[0].message")
                            .value("Upload the licence as a PDF, PNG or JPEG under 10 MB."));
        }
    }

    // ── checkout and the door ───────────────────────────────────────────────────────────────────────────────

    @Nested
    class Checkout {

        @Test
        void aCartWithAlcoholAsksForAnAgeCheckFirst() throws Exception {
            licensed();
            var customer = data.user("Ada Adult");
            addToCart(customer, wine).andExpect(status().isCreated());
            mvc.perform(get("/api/v1/me/checkout").param("market", MARKET).with(TestJwt.customerWithMfa(customer)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.age.required").value(true))
                    .andExpect(jsonPath("$.age.classes[0]").value("alcohol"))
                    .andExpect(jsonPath("$.age.state").value("none"));
            mvc.perform(json(post("/api/v1/me/checkout/quote"), checkout("AB")).with(TestJwt.customerWithMfa(customer)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.age.minimumAge").value(18));
            mvc.perform(json(post("/api/v1/me/checkout/quote"), checkout("ON")).with(TestJwt.customerWithMfa(customer)))
                    .andExpect(jsonPath("$.age.minimumAge").value(19));
            start(customer)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("age_verification_required"))
                    .andExpect(
                            jsonPath("$.detail")
                                    .value(
                                            "Your cart has age-restricted items. Confirm you're 18 or older with photo ID to continue."));

            verifyAge(customer, "age_30");
            mvc.perform(get("/api/v1/me/age-verification").with(TestJwt.customerWithMfa(customer)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("verified"))
                    .andExpect(jsonPath("$.overAge").value(21)) // capped at the strictest age any province asks
                    .andExpect(jsonPath("$.method").value("fake"));
            // nothing about the document is kept, and the provider's session is gone
            assertThat(jdbc.sql(
                                    "select count(*) from restricted.age_verifications where user_id = ? and session_id is null")
                            .params(customer)
                            .query(Long.class)
                            .single())
                    .isEqualTo(1);
            mvc.perform(json(post("/api/v1/me/age-verification"), "{\"returnTo\":\"web\"}")
                            .with(TestJwt.customerWithMfa(customer)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("age_already_verified"));
            start(customer).andExpect(status().isCreated());
        }

        @Test
        void anUnderAgeCustomerCantBuyAndACartWithoutRestrictedItemsIsUntouched() throws Exception {
            licensed();
            var teen = data.user("Theo Teen");
            verifyAge(teen, "age_16");
            addToCart(teen, bread).andExpect(status().isCreated());
            mvc.perform(get("/api/v1/me/checkout").param("market", MARKET).with(TestJwt.customerWithMfa(teen)))
                    .andExpect(jsonPath("$.age.required").value(false));
            start(teen).andExpect(status().isCreated());
            addToCart(teen, wine).andExpect(status().isCreated());
            start(teen)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("age_under_minimum"));
        }

        @Test
        void failedChecksAreKeptAsACodeAndValidationApplies() throws Exception {
            var customer = data.user("Fay Failed");
            verifyAge(customer, "document_expired");
            mvc.perform(get("/api/v1/me/age-verification").with(TestJwt.customerWithMfa(customer)))
                    .andExpect(jsonPath("$.state").value("failed"))
                    .andExpect(jsonPath("$.lastError").value("document_expired"));
            mvc.perform(json(post("/api/v1/me/age-verification"), "{\"returnTo\":\"tv\"}")
                            .with(TestJwt.customerWithMfa(customer)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message")
                            .value("Give the address to come back to after the ID check."));
            mvc.perform(get("/api/v1/me/age-verification")).andExpect(status().isUnauthorized());
        }

        @Test
        void theCourierConfirmsTheIdAtTheDoor() throws Exception {
            licensed();
            var customer = data.user("Ada Adult");
            verifyAge(customer, "age_30");
            var orderId = placeWine(customer);
            assertThat(jdbc.sql("select id_check_age from orders.orders where id = ?")
                            .params(orderId)
                            .query(Integer.class)
                            .single())
                    .isEqualTo(18);
            var courier = courierOnShift("Kai Courier");
            var run = pickedUp(orderId, courier);
            assertThat(JsonPath.<JSONArray>read(run, "$.stops[?(@.kind == 'dropoff')].idCheck.age"))
                    .containsExactly(18);
            assertThat(JsonPath.<JSONArray>read(run, "$.stops[?(@.kind == 'dropoff')].idCheck.recipient"))
                    .containsExactly("Ada Adult");
            var drop = stop(run, "dropoff");
            var pin = jdbc.sql("select pin from fulfilment.deliveries where order_id = ?")
                    .params(orderId)
                    .query(String.class)
                    .single();
            var me = TestJwt.courier(courier);
            mvc.perform(json(
                                    post("/api/v1/courier/stops/{id}/dropoff", drop),
                                    "{\"proof\":\"pin\",\"pin\":\"%s\"}".formatted(pin))
                            .with(me))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(
                            jsonPath("$.errors[0].message")
                                    .value(
                                            "Confirm you checked government photo ID, the name matches and the person is of age."));
            mvc.perform(json(
                                    post("/api/v1/courier/stops/{id}/dropoff", drop),
                                    "{\"proof\":\"pin\",\"pin\":\"%s\",\"idCheck\":{\"idChecked\":true,\"recipientMatches\":true,\"ofAge\":true}}"
                                            .formatted(pin))
                            .with(me))
                    .andExpect(status().isOk());
            assertThat(jdbc.sql("""
                            select outcome, place, actor_role, required_age, province from restricted.handoff_checks
                             where order_id = ?""").params(orderId).query().singleRow())
                    .containsEntry("outcome", "passed")
                    .containsEntry("place", "door")
                    .containsEntry("actor_role", "courier")
                    .containsEntry("required_age", 18)
                    .containsEntry("province", "AB");
        }

        @Test
        void nobodyOfAgeAtTheDoorReturnsTheOrderAndRefundsTheGoods() throws Exception {
            licensed();
            var customer = data.user("Ned Nobody");
            verifyAge(customer, "age_30");
            var orderId = placeWine(customer);
            var courier = courierOnShift("Rae Runner");
            var me = TestJwt.courier(courier);
            var run = pickedUp(orderId, courier);
            var drop = stop(run, "dropoff");
            mvc.perform(json(post("/api/v1/courier/stops/{id}/refuse", drop), "{\"reason\":\"nobody\"}")
                            .with(me))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Choose why the order can't be handed over."));
            var after = body(
                    mvc.perform(json(post("/api/v1/courier/stops/{id}/refuse", drop), "{\"reason\":\"nobody_of_age\"}")
                                    .with(me))
                            .andExpect(status().isOk()));
            assertThat(JsonPath.<JSONArray>read(after, "$.stops[?(@.kind == 'return')].place.merchantId"))
                    .containsExactly(shop);
            assertThat(events.stream(DeliveryRefused.class))
                    .anyMatch(e -> e.aggregateId().equals(orderId));
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> jdbc.sql("select state from orders.orders where id = ?")
                            .params(orderId)
                            .query(String.class)
                            .single()
                            .equals("returned"));
            // the goods are refunded in full through the refund queue (auto-approved); the delivery fee is kept
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> jdbc.sql("""
                                            select count(*) from payments.refunds r join payments.escrows e on e.id = r.escrow_id
                                             where e.ref_type = 'order_line' and e.ref_id in
                                                   (select id from orders.order_lines where order_id = ?)""").params(orderId).query(Long.class).single() == 1);
            mvc.perform(post("/api/v1/courier/stops/{id}/returned", stop(after, "return"))
                            .with(me))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("done"));
            assertThat(jdbc.sql("select state from fulfilment.deliveries where order_id = ?")
                            .params(orderId)
                            .query(String.class)
                            .single())
                    .isEqualTo("returned");
            // trust & safety's report counts it
            mvc.perform(get("/api/v1/console/vetting/age-checks").with(TestJwt.staff(staff, StaffRole.TRUST_SAFETY)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.refusals.nobody_of_age").isNumber())
                    .andExpect(jsonPath("$.recent[?(@.orderId == '%s')].reason".formatted(orderId))
                            .value("nobody_of_age"));
            mvc.perform(get("/api/v1/console/vetting/age-checks").with(TestJwt.staff(staff, StaffRole.DISPATCH)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/console/vetting/age-checks")
                            .param("from", "2026-01-01T00:00:00Z")
                            .param("to", "2025-01-01T00:00:00Z")
                            .with(TestJwt.staff(staff, StaffRole.TRUST_SAFETY)))
                    .andExpect(status().isUnprocessableContent());
        }
    }
}
