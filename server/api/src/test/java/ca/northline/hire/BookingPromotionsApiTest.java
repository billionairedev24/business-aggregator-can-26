package ca.northline.hire;

import static ca.northline.hire.BookingFlow.tomorrowAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.Ids;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import ca.northline.tools.CategorySeeder;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Mobile gaps part 2: promo codes and points on a booking — the review step's price, the escrow's discount and funder,
 * the tax on the discounted price, the code's limits (per customer, overall), the points spent at confirmation, and
 * the 422 messages in English and French.
 */
class BookingPromotionsApiTest extends IntegrationTest {

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ca.northline.payments.api.CustomerCases cases;

    @Autowired
    ca.northline.payments.api.DisputeDecisions decisions;

    @Autowired
    ca.northline.payments.application.PaymentsJobs jobs;

    BookingFlow flow;
    String customer;
    String finance;

    @BeforeEach
    void setUp() {
        new CategorySeeder(dataSource).seed();
        flow = new BookingFlow(mvc, new HireFixtures(jdbc), "Promo Wrench");
        customer = data.user("Amara Osei");
        finance = data.user("Fin Ance");
    }

    /** A live code made in the console; returns the code. */
    String code(String body) throws Exception {
        var code = "T" + Ids.next().substring(18);
        var now = Instant.now();
        mvc.perform(post("/api/v1/console/promotions/codes")
                        .with(TestJwt.staff(finance, StaffRole.FINANCE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","startsAt":"%s","endsAt":"%s",%s}""".formatted(
                                        code, now.minus(Duration.ofHours(1)), now.plus(Duration.ofDays(7)), body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("live"));
        return code;
    }

    void points(String user, long points) {
        jdbc.sql("insert into trust.points_ledger (id, user_id, delta, ref_type, ref_id) values (?, ?, ?, 'earn', ?)")
                .params(Ids.next(), user, points, Ids.next())
                .update();
    }

    long wallet(String user) {
        return jdbc.sql("select coalesce(sum(delta), 0) from trust.points_ledger where user_id = ?")
                .params(user)
                .query(Long.class)
                .single();
    }

    @Test
    void aNorthlineCode_lowersThePriceAndTheTax_andTheEscrowKnowsWhoFundsIt() throws Exception {
        var code = code("""
                "kind":"percent","percent":20,"fundedBy":"northline","appliesTo":["service"]""");
        var holdId = flow.hold(customer, tomorrowAt(9));

        // the review step's price: $89 − 20 % = $71.20, GST 5 % on $71.20 = $3.56
        mvc.perform(post("/api/v1/me/bookings/price")
                        .with(TestJwt.customer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"holdId":"%s","serviceId":"%s","promoCode":"%s"}""".formatted(holdId, flow.service, code.toLowerCase(java.util.Locale.ROOT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceCents").value(8900))
                .andExpect(jsonPath("$.discountCents").value(1780))
                .andExpect(jsonPath("$.taxCents").value(356))
                .andExpect(jsonPath("$.totalCents").value(7476))
                .andExpect(jsonPath("$.promoCode").value(code));

        flow.checkout(customer, holdId, Map.of("promoCode", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceCents").value(8900))
                .andExpect(jsonPath("$.discountCents").value(1780))
                .andExpect(jsonPath("$.taxCents").value(356))
                .andExpect(jsonPath("$.totalCents").value(7476));
        var bookingId = flow.confirm(customer, holdId);

        var escrow = jdbc.sql("""
                        select amount_cents, tax_cents, discount_cents, discount_funded_by, fee_cents
                          from payments.escrows where ref_type = 'booking' and ref_id = ?""")
                .params(bookingId)
                .query((rs, _) -> new long[] {
                    rs.getLong(1),
                    rs.getLong(2),
                    rs.getLong(3),
                    "northline".equals(rs.getString(4)) ? 1 : 0,
                    rs.getLong(5)
                })
                .single();
        assertThat(escrow).containsExactly(7120, 356, 1780, 1, 801); // 9 % of the full $89
        assertThat(jdbc.sql("select state from promotions.redemptions where kind = 'service' and ref_id = ?")
                        .params(bookingId)
                        .query(String.class)
                        .single())
                .isEqualTo("redeemed");
        assertThat(jdbc.sql("select discount_cents from booking.bookings where id = ?")
                        .params(bookingId)
                        .query(Long.class)
                        .single())
                .isEqualTo(1780);

        // once per customer (the default limit), in English and in French
        var again = flow.hold(customer, tomorrowAt(11));
        flow.checkout(customer, again, Map.of("promoCode", code))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("promoCode"))
                .andExpect(jsonPath("$.errors[0].message").value("You've already used this code."));
        mvc.perform(post("/api/v1/me/bookings/price")
                        .with(TestJwt.customer(customer))
                        .header("Accept-Language", "fr-CA")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"holdId":"%s","serviceId":"%s","promoCode":"%s"}""".formatted(again, flow.service, code)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Vous avez déjà utilisé ce code."));
    }

    @Test
    void codesAreChecked_unknownMinimumSpendOtherKindsAndTheOverallLimit() throws Exception {
        var holdId = flow.hold(customer, tomorrowAt(13));
        var price = (java.util.function.Function<String, String>) c -> """
                {"holdId":"%s","serviceId":"%s","promoCode":"%s"}""".formatted(holdId, flow.service, c);
        mvc.perform(post("/api/v1/me/bookings/price")
                        .with(TestJwt.customer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(price.apply("NOPE-NOPE")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("This code isn't valid."));

        var big = code("""
                "kind":"amount","amountCents":1000,"minSpendCents":10000,"fundedBy":"northline","appliesTo":["service"]""");
        mvc.perform(post("/api/v1/me/bookings/price")
                        .with(TestJwt.customer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(price.apply(big)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Spend at least $100.00 to use this code."));
        mvc.perform(post("/api/v1/me/bookings/price")
                        .with(TestJwt.customer(customer))
                        .header("Accept-Language", "fr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(price.apply(big)))
                .andExpect(jsonPath("$.errors[0].message").value("Dépensez au moins 100,00 $ pour utiliser ce code."));

        var shopOnly = code("""
                "kind":"amount","amountCents":500,"fundedBy":"northline","appliesTo":["goods"]""");
        mvc.perform(post("/api/v1/me/bookings/price")
                        .with(TestJwt.customer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(price.apply(shopOnly)))
                .andExpect(jsonPath("$.errors[0].message").value("This code doesn't apply to this order."));

        // another business's own code doesn't apply here
        var other = data.merchant("provider", "Other Garage");
        var theirs = code("""
                "kind":"amount","amountCents":500,"fundedBy":"merchant","merchantId":"%s","appliesTo":["service"]""".formatted(other));
        mvc.perform(post("/api/v1/me/bookings/price")
                        .with(TestJwt.customer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(price.apply(theirs)))
                .andExpect(jsonPath("$.errors[0].message").value("This code doesn't apply to this order."));

        // one use overall: a second customer finds it used up while the first one's checkout holds it
        var once = code("""
                "kind":"amount","amountCents":500,"totalLimit":1,"fundedBy":"merchant","merchantId":"%s",
                "appliesTo":["service"]""".formatted(flow.provider.merchantId()));
        flow.checkout(customer, holdId, Map.of("promoCode", once)).andExpect(status().isOk());
        var second = data.user("Second Customer");
        var theirHold = flow.hold(second, tomorrowAt(15));
        flow.checkout(second, theirHold, Map.of("promoCode", once))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("This code has been fully used."));
    }

    @Test
    void pointsPayUpToHalf_andLeaveTheWalletWhenTheBookingIsConfirmed() throws Exception {
        points(customer, 10_000); // $100 of points
        var holdId = flow.hold(customer, tomorrowAt(17));
        mvc.perform(post("/api/v1/me/bookings/price")
                        .with(TestJwt.customer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"holdId":"%s","serviceId":"%s","usePoints":true}""".formatted(holdId, flow.service)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pointsAvailable").value(10_000))
                .andExpect(jsonPath("$.pointsCents").value(4450)) // 50 % of $89
                .andExpect(jsonPath("$.points").value(4450))
                .andExpect(jsonPath("$.taxCents").value(445)) // the tax stays on the full price
                .andExpect(jsonPath("$.totalCents").value(8900 + 445 - 4450));

        flow.checkout(customer, holdId, Map.of("usePoints", true)).andExpect(status().isOk());
        assertThat(wallet(customer)).isEqualTo(10_000); // held, not yet spent
        var bookingId = flow.confirm(customer, holdId);
        assertThat(wallet(customer)).isEqualTo(10_000 - 4450);
        assertThat(jdbc.sql("select points_cents from payments.escrows where ref_type = 'booking' and ref_id = ?")
                        .params(bookingId)
                        .query(Long.class)
                        .single())
                .isEqualTo(4450);
    }

    @Test
    void aRefundGivesThePointsBackToTheWallet_once() throws Exception {
        points(customer, 2_000);
        var bookingId = flow.book(customer, tomorrowAt(19), Map.of("usePoints", true));
        assertThat(wallet(customer)).isZero();
        flow.job(bookingId, "en-route", Map.of()).andExpect(status().isOk());
        flow.job(bookingId, "on-site", Map.of()).andExpect(status().isOk());
        flow.job(bookingId, "complete", Map.of("report", "Done.")).andExpect(status().isOk());
        var escrowId = jdbc.sql("select id from payments.escrows where ref_type = 'booking' and ref_id = ?")
                .params(bookingId)
                .query(String.class)
                .single();
        // the completion reaches the escrow first (its 48 h clock starts)
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .until(() -> jdbc.sql("select fulfilled_at is not null from payments.escrows where id = ?")
                        .params(escrowId)
                        .query(Boolean.class)
                        .single());
        // half of the $89 back: half of the $20 of points back to the wallet
        var refund = cases.requestReview(escrowId, customer, 4_450, "Half the job");
        decisions.decideRefund(refund, true, "agent-" + Ids.next());
        jobs.payRefundQueue();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> wallet(customer) == 1_000);
        assertThat(jdbc.sql("select points_cents from payments.refunds where id = ?")
                        .params(refund)
                        .query(Long.class)
                        .single())
                .isEqualTo(1_000);
        assertThat(jdbc.sql("select points_returned_cents from promotions.redemption_lines where escrow_ref_id = ?")
                        .params(bookingId)
                        .query(Long.class)
                        .single())
                .isEqualTo(1_000);
    }
}
