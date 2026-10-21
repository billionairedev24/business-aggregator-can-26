package ca.northline.standing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.booking.api.BookingProgressed.BookingCompleted;
import ca.northline.catalogue.api.ServiceOffers;
import ca.northline.identity.api.PersonDirectory;
import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.api.EscrowLifecycle;
import ca.northline.shared.Ids;
import ca.northline.shared.JdbcTimes;
import ca.northline.shared.security.MerchantRole;
import ca.northline.shared.security.StaffRole;
import ca.northline.standing.application.StandingUseCases.EnforceStanding;
import ca.northline.support.IntegrationTest;
import ca.northline.support.OperationsFixtures;
import ca.northline.support.SettingsFixtures;
import ca.northline.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S-156: the automatic consequences S-93 configured and S-82 couldn't apply — provider no-shows pause instant book
 * (the Studio switch respects the pause; search and checkout read the effective value) and it comes back once the
 * window holds fewer; customer no-shows put a warning on the customer providers see and stop instant booking; a job
 * completed without a photo is released later. Staff lifts hold. No-shows come from reports staff confirm.
 */
class StandingEnforcementTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    EnforceStanding enforce;

    @Autowired
    ServiceOffers offers;

    @Autowired
    PersonDirectory people;

    @Autowired
    EscrowLifecycle escrows;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TransactionTemplate tx;

    String owner;
    String ownerEmail;
    String business;
    String staff;

    @BeforeEach
    void business() {
        var fx = new SettingsFixtures(jdbc);
        ownerEmail = SettingsFixtures.email("owner");
        owner = fx.person("Kay Garage", ownerEmail, null, "totp");
        business = provider("No-show Mechanics");
        data.member(business, owner, MerchantRole.OWNER);
        staff = data.user("Dev Kaur");
    }

    String provider(String name) {
        var id = data.merchant("provider", name);
        jdbc.sql("""
                        insert into catalogue.services (id, merchant_id, name, pricing_mode, price_cents, duration_min,
                               instant_book, vetting, status)
                        values (?, ?, 'Oil change', 'fixed', 6900, 45, true, 'approved', 'live')""").params(Ids.next(), id).update();
        return id;
    }

    /** A no-show flag staff confirmed {@code daysAgo}: the provider's ({@code no_show}) or a customer's. */
    void confirmed(String rule, String merchantId, @org.jspecify.annotations.Nullable String customerId, int daysAgo) {
        jdbc.sql("""
                        insert into trust.flags (id, target_type, target_id, rule, evidence, state, action, merchant_id,
                                                 decided_at, decided_by, created_at)
                        values (?, 'booking', ?, ?, cast(? as jsonb), 'actioned', 'confirm', ?, ?, ?, ?)""")
                .params(
                        Ids.next(),
                        Ids.next(),
                        rule,
                        customerId == null ? "{}" : "{\"customerId\":\"" + customerId + "\"}",
                        merchantId,
                        JdbcTimes.ts(Instant.now().minus(Duration.ofDays(daysAgo))),
                        staff,
                        JdbcTimes.ts(Instant.now().minus(Duration.ofDays(daysAgo + 1))))
                .update();
    }

    boolean instantBook(String merchantId) {
        return offers.ofMerchant(merchantId, "en").stream().allMatch(ServiceOffers.Offer::instantBook);
    }

    org.springframework.test.web.servlet.ResultActions rules(String acceptMode) throws Exception {
        return mvc.perform(put("/api/v1/merchants/{m}/availability/rules", business)
                .with(TestJwt.member(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"intervalMin":30,"bufferMin":0,"minNoticeMin":60,"horizonDays":14,"maxJobsPerDay":99,
                         "acceptMode":"%s","rescheduleFreeMin":180,"lateCancelFeeCents":0,"serviceAreas":[]}""".formatted(acceptMode)));
    }

    String standing(String merchantId, String who) throws Exception {
        return mvc.perform(get("/api/v1/merchants/{m}/standing", merchantId).with(TestJwt.member(who)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    @Test
    void threeConfirmedProviderNoShows_pauseInstantBook_theSwitchRespectsIt_andStaffCanLiftIt() throws Exception {
        confirmed("no_show", business, null, 2);
        confirmed("no_show", business, null, 5);
        enforce.run();
        assertThat(instantBook(business)).as("two are below the rule's three").isTrue();

        confirmed("no_show", business, null, 9);
        enforce.run();
        assertThat(instantBook(business)).isFalse();
        mvc.perform(get("/api/v1/merchants/{m}/instant-book", business).with(TestJwt.member(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(true))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.effective").value(false))
                .andExpect(jsonPath("$.pausedCause").value("provider_no_shows"));
        var json = standing(business, owner);
        assertThat(JsonPath.<List<String>>read(json, "$.active[?(@.kind == 'instant_book_off')].reason"))
                .singleElement()
                .satisfies(r -> assertThat(r).startsWith("3 confirmed no-shows in the last 30 days"));
        assertThat(JsonPath.<List<Boolean>>read(json, "$.active[?(@.kind == 'instant_book_off')].appealable"))
                .containsExactly(true);
        await().atMost(Duration.ofSeconds(10))
                .until(() -> emails.to(ownerEmail).stream()
                        .anyMatch(m -> m.subject().equals("Instant book is paused for No-show Mechanics")));

        // its switch ("How bookings come in") can go off while paused, not back on
        rules("approve").andExpect(status().isOk());
        rules("instant")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("instant_book_paused"))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        "Northline paused instant book for your business. Standing shows why and how to appeal."));
        mvc.perform(get("/api/v1/merchants/{m}/instant-book", business).with(TestJwt.member(owner)))
                .andExpect(jsonPath("$.enabled").value(false));

        // staff lift it; the rule leaves it alone for its window
        var id = JsonPath.<List<String>>read(json, "$.active[?(@.kind == 'instant_book_off')].id")
                .getFirst();
        mvc.perform(post("/api/v1/console/standing/consequences/{id}/lift", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Two of the no-shows were the customer's mistake.\"}")
                        .with(TestJwt.staff(staff, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isOk());
        assertThat(instantBook(business)).as("its own switch is off").isFalse();
        rules("instant").andExpect(status().isOk());
        assertThat(instantBook(business)).isTrue();
        enforce.run();
        assertThat(instantBook(business)).isTrue();
        await().atMost(Duration.ofSeconds(10))
                .until(() -> emails.to(ownerEmail).stream()
                        .anyMatch(m -> m.subject().equals("Instant book is no longer paused for No-show Mechanics")));
    }

    @Test
    void instantBookComesBackByItself_onceTheWindowHoldsFewerNoShows() throws Exception {
        var other = provider("Recovering Mechanics");
        data.member(other, owner, MerchantRole.OWNER);
        for (var days : List.of(1, 2, 3)) {
            confirmed("no_show", other, null, days);
        }
        enforce.run();
        assertThat(instantBook(other)).isFalse();
        jdbc.sql(
                        "update trust.flags set decided_at = now() - interval '40 days' where merchant_id = ? and rule = 'no_show'")
                .params(other)
                .update();
        enforce.run();
        assertThat(instantBook(other)).isTrue();
        var json = standing(other, owner);
        assertThat(JsonPath.<List<Boolean>>read(json, "$.past[?(@.kind == 'instant_book_off')].liftedByStaff"))
                .containsExactly(false);
    }

    @Test
    void customerNoShows_putAWarningProvidersSee_andStopInstantBooking_untilStaffLiftIt() throws Exception {
        // the default of 2: TrustRulesApiTest (same database) leaves the rule at 3 when it runs first
        jdbc.sql("delete from trust.rules where key = 'customer_no_shows'").update();
        var customer = data.user("Casey Late");
        confirmed("customer_no_show", business, customer, 10);
        enforce.run();
        assertThat(people.people(List.of(customer)).get(customer).noShows()).isZero();

        confirmed("customer_no_show", provider("Another Garage"), customer, 3);
        enforce.run();
        assertThat(people.people(List.of(customer)).get(customer).noShows()).isEqualTo(2);
        var body = mvc.perform(get("/api/v1/me/standing").with(TestJwt.customer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active[0].kind").value("no_show_warning"))
                .andExpect(jsonPath("$.active[0].appealable").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(JsonPath.<String>read(body, "$.active[0].reason"))
                .startsWith("2 confirmed no-shows in the last 12 months");
        mvc.perform(get("/api/v1/me/standing")).andExpect(status().isUnauthorized());

        String id = JsonPath.read(body, "$.active[0].id");
        mvc.perform(post("/api/v1/console/standing/consequences/{id}/lift", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"The appointments were moved by the provider.\"}")
                        .with(TestJwt.staff(staff, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isOk());
        assertThat(people.people(List.of(customer)).get(customer).noShows()).isZero();
        enforce.run();
        assertThat(people.people(List.of(customer)).get(customer).noShows())
                .as("a staff lift holds for the window")
                .isZero();
    }

    @Test
    void aBusinessReportsACustomerNoShow_fifteenMinutesAfterTheStart_once() throws Exception {
        var ops = new OperationsFixtures(jdbc);
        var customer = data.user("Casey Late");
        var late = ops.job(business, null, customer, Instant.now().minus(Duration.ofMinutes(30)), "confirmed");
        var soon = ops.job(business, null, customer, Instant.now().minus(Duration.ofMinutes(5)), "confirmed");
        var done = ops.job(business, null, customer, Instant.now().minus(Duration.ofHours(3)), "completed");
        var url = "/api/v1/merchants/{m}/jobs/{j}/customer-no-show";
        mvc.perform(post(url, business, late).with(TestJwt.member(data.user("Stranger"))))
                .andExpect(status().isForbidden());
        mvc.perform(post(url, business, soon).with(TestJwt.member(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("too_early"));
        mvc.perform(post(url, business, done).with(TestJwt.member(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_reportable"));
        mvc.perform(post(url, business, late).with(TestJwt.member(owner))).andExpect(status().isNoContent());
        mvc.perform(post(url, business, late).with(TestJwt.member(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already_reported"));
        assertThat(jdbc.sql("""
                                select evidence ->> 'customerId' from trust.flags
                                 where rule = 'customer_no_show' and target_id = ? and state = 'open'""").params(late).query(String.class).single()).isEqualTo(customer);
    }

    @Test
    void aJobCompletedWithoutAPhoto_isReleased48HoursLater_untilStaffLiftTheDelay() throws Exception {
        var bookingId = Ids.next();
        var escrowId = escrows.hold(new EscrowLifecycle.Hold(
                business,
                EscrowKind.SERVICE,
                "booking",
                bookingId,
                5_000,
                250,
                "cust-" + Ids.next(),
                "M. Tran",
                "Job",
                null,
                null,
                null,
                null,
                "pi_" + Ids.next(),
                Instant.now()));
        var at = Instant.now().truncatedTo(ChronoUnit.MICROS);
        tx.executeWithoutResult(
                _ -> publisher.publishEvent(new BookingCompleted(Ids.next(), at, bookingId, business, owner, 0)));
        await().atMost(Duration.ofSeconds(10))
                .until(() -> releaseAt(escrowId) != null && releaseAt(escrowId).isAfter(at.plus(Duration.ofDays(3))));
        assertThat(Duration.between(at, releaseAt(escrowId))).isEqualTo(Duration.ofDays(4));
        await().atMost(Duration.ofSeconds(10))
                .until(() -> JsonPath.<List<String>>read(
                                        standing(business, owner), "$.active[?(@.kind == 'photo_delay')].id")
                                .size()
                        == 1);
        var id = JsonPath.<List<String>>read(standing(business, owner), "$.active[?(@.kind == 'photo_delay')].id")
                .getFirst();
        mvc.perform(post("/api/v1/console/standing/consequences/{id}/lift", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"The photos were sent by message.\"}")
                        .with(TestJwt.staff(staff, StaffRole.TRUST_SAFETY)))
                .andExpect(status().isOk());
        assertThat(Duration.between(at, releaseAt(escrowId))).isEqualTo(Duration.ofDays(2));
    }

    Instant releaseAt(String escrowId) {
        return jdbc.sql("select release_at from payments.escrows where id = ?")
                .params(escrowId)
                .query((rs, _) ->
                        rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant())
                .optional()
                .orElse(null);
    }
}
