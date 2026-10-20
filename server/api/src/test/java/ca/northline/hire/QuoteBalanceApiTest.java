package ca.northline.hire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.booking.api.BookingBalanceFailed;
import ca.northline.booking.api.BookingBalanceHeld;
import ca.northline.hire.application.BalanceHolds;
import ca.northline.shared.Ids;
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
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-153 on deposit quotes: accepting holds the deposit and schedules the balance; the balance job holds it off-session
 * on the deposit's card (its own escrow, released with the job); a refusal is retried and tells both sides, the last one
 * leaves it {@code failed}; the customer can pay it from the booking; a promo code, points and a saved card work on the
 * acceptance as on the wizard; Account lists the requests. Fake gateway: Stripe's test payment methods decide.
 */
@RecordApplicationEvents
class QuoteBalanceApiTest extends IntegrationTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEvents events;

    @Autowired
    BalanceHolds balances;

    HireFixtures fx;
    HireFixtures.Provider wrench;
    String customer;

    @BeforeEach
    void setUp() {
        new CategorySeeder(dataSource).seed();
        fx = new HireFixtures(jdbc);
        wrench = fx.provider("Balance Wrench", "master", List.of("Beltline"));
        fx.service(wrench.merchantId(), BookingFlow.MECHANIC, "Alternator / starter", "quote", null, 120);
        customer = data.user("Amara Osei");
    }

    /** Within the 48 h lead, so the balance is due at once, and far enough for a retry 6 h later. */
    static Instant soon() {
        return Instant.now().plus(Duration.ofHours(40)).truncatedTo(ChronoUnit.HOURS);
    }

    String quote(String who, Instant at) throws Exception {
        var request = mvc.perform(post("/api/v1/me/quote-requests")
                        .with(TestJwt.customer(who))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "category",
                                "mobile-mechanic",
                                "providers",
                                List.of(wrench.slug()),
                                "description",
                                "Battery light on, car died twice this week.",
                                "vehicle",
                                Map.of("year", "2018", "make", "Honda", "model", "Civic")))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String requestId = JsonPath.read(request, "$.requestId");
        // $435.00 + GST $21.75 = $456.75; 25 % deposit $114.19 (tax share $5.44); balance $342.56 (tax $16.31)
        var sent = mvc.perform(post("/api/v1/merchants/{m}/quote-requests/{r}/quotes", wrench.merchantId(), requestId)
                        .with(TestJwt.member(wrench.owner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"kind":"part","description":"Alternator, remanufactured","qty":1,"unitCents":24000},
                                          {"kind":"labour","description":"Replace alternator","qty":1.5,"unitCents":13000}],
                                 "scope":"Replace the alternator.","proposedAt":"%s","durationMin":120,"validHours":72,
                                 "warranty":"parts_labour_12m","depositKind":"pct","depositBps":2500}
                                """.formatted(at)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(sent, "$.id");
    }

    static String visit(Map<String, Object> extra) throws Exception {
        var v = new java.util.LinkedHashMap<String, Object>(
                Map.of("addressLine", "1204 Sample St", "accessNote", "Gate 4471", "contactPhone", "+1 403 555 0123"));
        v.putAll(extra);
        return JSON.writeValueAsString(v);
    }

    String accept(String who, String quoteId, Map<String, Object> extra) throws Exception {
        mvc.perform(post("/api/v1/me/quotes/{id}/accept", quoteId)
                        .with(TestJwt.customerWithMfa(who))
                        .header("Idempotency-Key", "a-" + quoteId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(visit(extra)))
                .andExpect(status().isOk());
        var body = mvc.perform(post("/api/v1/me/quotes/{id}/accept/confirm", quoteId)
                        .with(TestJwt.customer(who))
                        .header("Idempotency-Key", "c-" + quoteId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(visit(extra)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.bookingId");
    }

    List<Object> balance(String bookingId) {
        return jdbc.sql("""
                        select balance_state, balance_cents, balance_tax_cents, balance_attempts,
                               coalesce(balance_failure, '')
                          from booking.bookings where id = ?""")
                .params(bookingId)
                .query((rs, _) ->
                        List.<Object>of(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getInt(4), rs.getString(5)))
                .single();
    }

    void depositCard(String bookingId, String paymentMethod) {
        jdbc.sql("""
                        update payments.payment_intents set payment_method_ref = ?
                         where ref_type = 'booking' and ref_id = ?""").params(paymentMethod, bookingId).update();
    }

    @Test
    void theBalanceIsHeldBeforeTheEvent_onTheDepositsCard_andReleasedWithTheJob() throws Exception {
        var quoteId = quote(customer, soon());
        mvc.perform(get("/api/v1/me/quotes/{id}", quoteId).with(TestJwt.customer(customer)))
                .andExpect(jsonPath("$.balance.totalCents").value(34256))
                .andExpect(jsonPath("$.balance.leadHours").value(48))
                .andExpect(jsonPath("$.cancellation.windowHours").exists());
        var bookingId = accept(customer, quoteId, Map.of());
        assertThat(balance(bookingId)).containsExactly("pending", 32625L, 1631L, 0, "");

        balances.holdDue();
        assertThat(balance(bookingId)).containsExactly("held", 32625L, 1631L, 0, "");
        assertThat(jdbc.sql("""
                        select amount_cents, tax_cents, state from payments.escrows
                         where ref_type = 'booking_balance' and ref_id = ?""")
                        .params(bookingId)
                        .query((rs, _) -> List.of(rs.getLong(1), rs.getLong(2), rs.getString(3)))
                        .single())
                .containsExactly(32625L, 1631L, "held");
        assertThat(events.stream(BookingBalanceHeld.class)
                        .filter(e -> e.aggregateId().equals(bookingId)))
                .singleElement()
                .satisfies(e -> assertThat(e.amountCents() + e.taxCents()).isEqualTo(34256));
        balances.holdDue(); // held once
        assertThat(events.stream(BookingBalanceHeld.class)
                        .filter(e -> e.aggregateId().equals(bookingId)))
                .hasSize(1);
        mvc.perform(get("/api/v1/me/bookings/{id}", bookingId).with(TestJwt.customer(customer)))
                .andExpect(jsonPath("$.balance.state").value("held"));

        for (var step : List.of("en-route", "on-site")) {
            mvc.perform(post("/api/v1/merchants/{m}/jobs/{id}/" + step, wrench.merchantId(), bookingId)
                            .with(TestJwt.member(wrench.owner()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/v1/merchants/{m}/jobs/{id}/complete", wrench.merchantId(), bookingId)
                        .with(TestJwt.member(wrench.owner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"report\":\"Alternator replaced.\"}"))
                .andExpect(status().isOk());
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(jdbc.sql("""
                                        select count(*) from payments.escrows
                                         where ref_id = ? and ref_type in ('booking', 'booking_balance')
                                           and fulfilled_at is not null""")
                                .params(bookingId)
                                .query(Long.class)
                                .single())
                        .isEqualTo(2L));
    }

    @Test
    void aRefusalIsRetried_andTellsBothSides_theLastOneLeavesItFailed_theCustomerCanStillPay() throws Exception {
        var bookingId = accept(customer, quote(customer, soon()), Map.of());
        depositCard(bookingId, "pm_card_chargeDeclined");

        balances.holdDue();
        assertThat(balance(bookingId)).containsExactly("pending", 32625L, 1631L, 1, "declined");
        assertThat(events.stream(BookingBalanceFailed.class)
                        .filter(e -> e.aggregateId().equals(bookingId)))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.attempt()).isEqualTo(1);
                    assertThat(e.retryAt()).isNotNull();
                    assertThat(e.reason()).isEqualTo("declined");
                });
        balances.holdDue(); // not due again before the retry
        assertThat(balance(bookingId)).containsExactly("pending", 32625L, 1631L, 1, "declined");

        depositCard(bookingId, "pm_card_authenticationRequired");
        jdbc.sql("update booking.bookings set balance_attempts = 2, balance_next_at = now() where id = ?")
                .params(bookingId)
                .update();
        balances.holdDue();
        assertThat(balance(bookingId)).containsExactly("failed", 32625L, 1631L, 3, "authentication_required");
        assertThat(events.stream(BookingBalanceFailed.class)
                        .filter(e -> e.aggregateId().equals(bookingId)))
                .last()
                .satisfies(e -> assertThat(e.retryAt()).isNull());
        // the attempt that needed the customer was canceled, never left holding the card
        assertThat(jdbc.sql("""
                        select count(*) from payments.payment_intents
                         where ref_type = 'booking_balance' and ref_id = ? and state <> 'canceled'""").params(bookingId).query(Long.class).single()).isZero();

        // the customer pays it from the booking page
        mvc.perform(post("/api/v1/me/bookings/{id}/balance", bookingId)
                        .with(TestJwt.customer(customer))
                        .header("Idempotency-Key", "pay-" + bookingId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("second_factor_required"));
        mvc.perform(post("/api/v1/me/bookings/{id}/balance/confirm", bookingId)
                        .with(TestJwt.customer(customer))
                        .header("Idempotency-Key", "early-" + bookingId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_started"));
        mvc.perform(post("/api/v1/me/bookings/{id}/balance", bookingId)
                        .with(TestJwt.customerWithMfa(customer))
                        .header("Idempotency-Key", "pay2-" + bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCents").value(34256))
                .andExpect(jsonPath("$.status").value("authorized"));
        mvc.perform(post("/api/v1/me/bookings/{id}/balance/confirm", bookingId)
                        .with(TestJwt.customer(customer))
                        .header("Idempotency-Key", "conf-" + bookingId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance.state").value("held"));
        mvc.perform(post("/api/v1/me/bookings/{id}/balance", bookingId)
                        .with(TestJwt.customerWithMfa(customer))
                        .header("Idempotency-Key", "pay3-" + bookingId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("no_balance"));
        mvc.perform(post("/api/v1/me/bookings/{id}/balance", bookingId)
                        .with(TestJwt.customerWithMfa(data.user("Nosy")))
                        .header("Idempotency-Key", "nosy-" + bookingId))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancellingADepositQuote_releasesTheHeldBalanceToo() throws Exception {
        var bookingId = accept(customer, quote(customer, soon()), Map.of());
        balances.holdDue();
        mvc.perform(post("/api/v1/me/bookings/{id}/cancel", bookingId)
                        .with(TestJwt.customer(customer))
                        .header("Idempotency-Key", "cx-" + bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("cancelled"))
                .andExpect(jsonPath("$.balance.state").value("cancelled"));
        assertThat(jdbc.sql("""
                        select e.state || '/' || pi.state from payments.escrows e
                          join payments.payment_intents pi on pi.id = e.payment_intent_id
                         where e.ref_type = 'booking_balance' and e.ref_id = ?""").params(bookingId).query(String.class).single()).isEqualTo("refunded/canceled");
    }

    @Test
    void aCodeAndASavedCardWorkOnTheAcceptance_andAccountListsTheRequests() throws Exception {
        var finance = data.user("Fin Ance");
        var code = "Q" + Ids.next().substring(18);
        var now = Instant.now();
        mvc.perform(post("/api/v1/console/promotions/codes")
                        .with(TestJwt.staff(finance, StaffRole.FINANCE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","startsAt":"%s","endsAt":"%s","kind":"percent","percent":20,
                                 "fundedBy":"northline","appliesTo":["service"]}""".formatted(code, now.minus(Duration.ofHours(1)), now.plus(Duration.ofDays(7)))))
                .andExpect(status().isCreated());
        var quoteId = quote(customer, soon());
        // the deposit $108.75 − 20 % = $87.00, tax 5 % = $4.35: $91.35 now; the balance is unchanged
        mvc.perform(post("/api/v1/me/quotes/{id}/price", quoteId)
                        .with(TestJwt.customer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"promoCode\":\"%s\"}".formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discountCents").value(2175))
                .andExpect(jsonPath("$.taxCents").value(435))
                .andExpect(jsonPath("$.heldCents").value(9135))
                .andExpect(jsonPath("$.balance.totalCents").value(34256));
        mvc.perform(post("/api/v1/me/quotes/{id}/accept", quoteId)
                        .with(TestJwt.customerWithMfa(customer))
                        .header("Idempotency-Key", "card-" + quoteId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(visit(Map.of("paymentMethodId", "pm_someone_elses"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'paymentMethodId')].message")
                        .value("Choose one of your saved cards."));
        var bookingId = accept(customer, quoteId, Map.of("promoCode", code));
        assertThat(jdbc.sql("""
                        select amount_cents, tax_cents, discount_cents, discount_funded_by from payments.escrows
                         where ref_type = 'booking' and ref_id = ?""")
                        .params(bookingId)
                        .query((rs, _) -> List.of(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4)))
                        .single())
                .containsExactly(8700L, 435L, 2175L, "northline");
        assertThat(jdbc.sql("select state from promotions.redemptions where kind = 'service' and ref_id = ?")
                        .params(bookingId)
                        .query(String.class)
                        .single())
                .isEqualTo("redeemed");

        var waiting = mvc.perform(post("/api/v1/me/quote-requests")
                        .with(TestJwt.customer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "category",
                                "mobile-mechanic",
                                "providers",
                                List.of(wrench.slug()),
                                "description",
                                "Brakes squeal when it rains.",
                                "vehicle",
                                Map.of("year", "2018", "make", "Honda", "model", "Civic")))))
                .andReturn()
                .getResponse()
                .getContentAsString();
        mvc.perform(get("/api/v1/me/quote-requests").with(TestJwt.customer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(JsonPath.<String>read(waiting, "$.requestId")))
                .andExpect(jsonPath("$.items[0].status").value("waiting"))
                .andExpect(jsonPath("$.items[1].status").value("accepted"))
                .andExpect(jsonPath("$.items[1].bookingId").value(bookingId))
                .andExpect(jsonPath("$.items[1].href").value("/bookings/" + bookingId));
    }
}
