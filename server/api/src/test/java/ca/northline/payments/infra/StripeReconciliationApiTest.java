package ca.northline.payments.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.payments.application.StripeBalance;
import ca.northline.region.api.Regions;
import ca.northline.shared.Ids;
import ca.northline.shared.JdbcTimes;
import ca.northline.shared.security.StaffRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * S-85: the daily Stripe ↔ ledger reconciliation. Each test takes its own day in the past (no other test posts to the
 * ledger then): a charge, a refund and a payout that agree, then what makes a day disagree — a Stripe charge the
 * ledger lacks, a posting whose charge Stripe doesn't know — and finance resolving it, exporting it, and the roles.
 * Stripe's side is the fake (it mirrors the ledger) plus what the test adds.
 */
class StripeReconciliationApiTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    FakeStripeBalance stripe;

    @Autowired
    Regions regions;

    String staff;
    LocalDate day;
    String suffix;

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @BeforeEach
    void day() {
        staff = data.user("Marc Finance");
        // a unique past day per test run
        day = LocalDate.of(2001, 1, 1).plusDays(Math.floorMod(Ids.next().hashCode(), 7000));
        suffix = Ids.next().substring(16).toLowerCase(Locale.ROOT);
        jdbc.sql("delete from payments.reconciliation_days where day = ?")
                .params(day)
                .update();
    }

    Instant at(int hour) {
        return day.atTime(LocalTime.of(hour, 0)).atZone(regions.platformZone()).toInstant();
    }

    /** A captured charge: PaymentIntent + escrow + the stripe_balance debit. */
    void charge(@Nullable String stripeCharge, long cents) {
        var pi = Ids.next();
        var escrow = Ids.next();
        jdbc.sql("""
                        insert into payments.payment_intents (id, stripe_pi, amount_cents, currency, capture_method, state, stripe_charge)
                        values (?, ?, ?, 'CAD', 'manual', 'captured', ?)""").params(pi, "pi_" + pi, cents, stripeCharge).update();
        jdbc.sql("""
                        insert into payments.escrows (id, payment_intent_id, ref_type, ref_id, merchant_id, amount_cents, state)
                        values (?, ?, 'booking', ?, ?, ?, 'held')""").params(escrow, pi, Ids.next(), Ids.next(), cents).update();
        posting("stripe_balance", cents, 0, "escrow", escrow, 10);
        posting("escrow", 0, cents, "escrow", escrow, 10);
    }

    void posting(String account, long debit, long credit, String refType, String refId, int hour) {
        jdbc.sql("""
                        insert into payments.ledger_entries (id, account, debit_cents, credit_cents, ref_type, ref_id, at)
                        values (?, ?, ?, ?, ?, ?, ?)""")
                .params(Ids.next(), account, debit, credit, refType, refId, JdbcTimes.ts(at(hour)))
                .update();
    }

    void refund(String stripeRefund, long cents) {
        var id = Ids.next();
        jdbc.sql("insert into payments.refunds (id, amount_cents, state, stripe_refund) values (?, ?, 'paid', ?)")
                .params(id, cents, stripeRefund)
                .update();
        posting("stripe_balance", 0, cents, "refund", id, 12);
        posting("escrow", cents, 0, "refund", id, 12);
    }

    void payout(String stripePayout, long cents) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into payments.payouts (id, merchant_id, stripe_payout, amount_cents, kind, fee_cents, state, created_at)
                        values (?, ?, ?, ?, 'scheduled', 0, 'paid', ?)""")
                .params(id, Ids.next(), stripePayout, cents, JdbcTimes.ts(at(15)))
                .update();
        posting("stripe_balance", 0, cents, "payout", id, 15);
    }

    @Test
    void aDayThatAgreesIsMatchedAndOneThatDoesntListsItsDifferences() throws Exception {
        charge("ch_ok_" + suffix, 10_000);
        refund("re_ok_" + suffix, 2_000);
        payout("po_ok_" + suffix, 5_000);
        mvc.perform(json(post("/api/v1/console/payments/reconciliation/run"), "{\"day\":\"%s\"}".formatted(day))
                        .with(TestJwt.staff(staff, StaffRole.FINANCE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("matched"))
                .andExpect(jsonPath("$.stripeCents").value(3_000))
                .andExpect(jsonPath("$.ledgerCents").value(3_000))
                .andExpect(jsonPath("$.items").value(3))
                .andExpect(jsonPath("$.mismatches").value(0));

        // Stripe has a charge the ledger lacks; the ledger has a capture whose charge Stripe doesn't know
        stripe.add(new StripeBalance.Txn("txn_" + suffix, "charge", "ch_late_" + suffix, 1_250, 66, at(11)));
        charge(null, 700);
        mvc.perform(json(post("/api/v1/console/payments/reconciliation/run"), "{\"day\":\"%s\"}".formatted(day))
                        .with(TestJwt.staff(staff, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("mismatch"))
                .andExpect(jsonPath("$.stripeCents").value(4_250))
                .andExpect(jsonPath("$.ledgerCents").value(3_700))
                .andExpect(jsonPath("$.varianceCents").value(550))
                .andExpect(jsonPath("$.feeCents").value(66))
                .andExpect(jsonPath("$.mismatches").value(2));
        mvc.perform(get("/api/v1/console/payments/reconciliation/{day}", day)
                        .with(TestJwt.staff(staff, StaffRole.FINANCE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.items[?(@.stripeId == 'ch_late_%s')].status".formatted(suffix))
                        .value("missing_in_ledger"))
                .andExpect(jsonPath("$.items[?(@.ledgerCents == 700)].status").value("missing_at_stripe"))
                .andExpect(jsonPath("$.items[?(@.stripeId == 'po_ok_%s')].status".formatted(suffix))
                        .value("matched"))
                .andExpect(jsonPath("$.items[0].status").value(org.hamcrest.Matchers.not("matched")));
        assertThat(audited("payments.reconciliation_run")).isEqualTo(2);

        // finance resolves the day with a note, then exports it
        mvc.perform(json(
                                post("/api/v1/console/payments/reconciliation/{day}/resolve", day),
                                "{\"note\":\"Late refund, booked the next day\"}")
                        .with(TestJwt.staff(staff, StaffRole.FINANCE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("resolved"))
                .andExpect(jsonPath("$.resolvedNote").value("Late refund, booked the next day"))
                .andExpect(jsonPath("$.resolvedBy").value(staff));
        assertThat(audited("payments.reconciliation_resolved")).isEqualTo(1);
        // running it again keeps the resolution while it still differs
        mvc.perform(json(post("/api/v1/console/payments/reconciliation/run"), "{\"day\":\"%s\"}".formatted(day))
                        .with(TestJwt.staff(staff, StaffRole.FINANCE)))
                .andExpect(jsonPath("$.status").value("resolved"));
        mvc.perform(get("/api/v1/console/payments/reconciliation")
                        .param("from", day.minusDays(1).toString())
                        .param("to", day.toString())
                        .with(TestJwt.staff(staff, StaffRole.FINANCE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].day").value(day.toString()));
        var csv = mvc.perform(get("/api/v1/console/payments/reconciliation/export")
                        .param("from", day.toString())
                        .param("to", day.toString())
                        .with(TestJwt.staff(staff, StaffRole.FINANCE)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(csv)
                .startsWith("day,status,stripe_cents,ledger_cents,variance_cents")
                .contains(day + ",resolved,4250,3700,550,66,charge,ch_late_" + suffix
                        + ",1250,,,,missing_in_ledger,\"Late refund, booked the next day\"");
        assertThat(audited("payments.reconciliation_exported")).isEqualTo(1);
        var ledger = mvc.perform(get("/api/v1/console/payments/reconciliation/ledger-export")
                        .param("from", day.toString())
                        .param("to", day.toString())
                        .with(TestJwt.staff(staff, StaffRole.ADMIN)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(ledger.lines()).first().isEqualTo("at,account,debit_cents,credit_cents,ref_type,ref_id");
        // the two captures, the refund and the payout
        assertThat(ledger.lines().filter(l -> l.contains(",stripe_balance,"))).hasSize(4);
    }

    @Test
    void rulesAndMessages() throws Exception {
        charge("ch_rules_" + suffix, 500);
        mvc.perform(json(post("/api/v1/console/payments/reconciliation/run"), "{\"day\":\"%s\"}".formatted(day))
                        .with(TestJwt.staff(staff, StaffRole.FINANCE)))
                .andExpect(jsonPath("$.status").value("matched"));
        mvc.perform(json(post("/api/v1/console/payments/reconciliation/{day}/resolve", day), "{\"note\":\"x\"}")
                        .with(TestJwt.staff(staff, StaffRole.FINANCE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_mismatched"));
        mvc.perform(json(post("/api/v1/console/payments/reconciliation/{day}/resolve", day), "{\"note\":\" \"}")
                        .with(TestJwt.staff(staff, StaffRole.FINANCE)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Say how the difference was resolved."));
        mvc.perform(json(
                                post("/api/v1/console/payments/reconciliation/run"),
                                "{\"day\":\"%s\"}".formatted(LocalDate.now().plusDays(2)))
                        .with(TestJwt.staff(staff, StaffRole.FINANCE)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose a day that has ended."));
        mvc.perform(json(post("/api/v1/console/payments/reconciliation/run"), "{\"day\":\"yesterday\"}")
                        .with(TestJwt.staff(staff, StaffRole.FINANCE)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose a day (YYYY-MM-DD)."));
        mvc.perform(get("/api/v1/console/payments/reconciliation")
                        .param("from", "2026-01-01")
                        .param("to", "2026-09-01")
                        .with(TestJwt.staff(staff, StaffRole.FINANCE)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("Choose at most 92 days, the first before the last."));
        mvc.perform(get("/api/v1/console/payments/reconciliation/{day}", day.minusDays(1))
                        .with(TestJwt.staff(staff, StaffRole.FINANCE)))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyAdminAndFinanceOpenItAndOnlyPayoutsChangeIt() throws Exception {
        for (var role :
                new StaffRole[] {StaffRole.TRUST_SAFETY, StaffRole.DISPATCH, StaffRole.SUPPORT, StaffRole.ANALYST}) {
            mvc.perform(get("/api/v1/console/payments/reconciliation").with(TestJwt.staff(staff, role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_role"));
            mvc.perform(get("/api/v1/console/payments/reconciliation/export").with(TestJwt.staff(staff, role)))
                    .andExpect(status().isForbidden());
            mvc.perform(json(post("/api/v1/console/payments/reconciliation/run"), "{\"day\":\"%s\"}".formatted(day))
                            .with(TestJwt.staff(staff, role)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/console/finance").with(TestJwt.staff(staff, role)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/console/payments/reconciliation/{day}", day)
                            .with(TestJwt.staff(staff, role)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/console/payments/reconciliation/ledger-export")
                            .with(TestJwt.staff(staff, role)))
                    .andExpect(status().isForbidden());
            mvc.perform(json(post("/api/v1/console/payments/reconciliation/{day}/resolve", day), "{\"note\":\"x\"}")
                            .with(TestJwt.staff(staff, role)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(json(post("/api/v1/console/payments/reconciliation/run"), "{\"day\":\"%s\"}".formatted(day))
                        .with(TestJwt.staffWithoutMfa(staff, StaffRole.FINANCE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
        assertThat(audited("payments.reconciliation_run")).isZero();
    }

    @Test
    void theFinanceFigures() throws Exception {
        mvc.perform(get("/api/v1/console/finance").with(TestJwt.staff(staff, StaffRole.FINANCE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.escrowHeldCents").isNumber())
                .andExpect(jsonPath("$.mix.takeCents").isNumber())
                .andExpect(jsonPath("$.mix.plusCents").doesNotExist())
                .andExpect(jsonPath("$.tiers", hasSize(3)))
                .andExpect(jsonPath("$.tiers[0].tier").value("master"))
                .andExpect(jsonPath("$.tiers[0].rateBps").value(900))
                .andExpect(jsonPath("$.tiers[2].rateBps").value(1500))
                .andExpect(jsonPath("$.tax.period").value(org.hamcrest.Matchers.matchesPattern("\\d{4}-Q[1-4]")))
                .andExpect(jsonPath("$.tax.nextFiling").isString());
    }

    long audited(String action) {
        return jdbc.sql(
                        "select count(*) from developer.audit_log where action = ? and actor_id = ? and merchant_id is null")
                .params(action, staff)
                .query(Long.class)
                .single();
    }
}
