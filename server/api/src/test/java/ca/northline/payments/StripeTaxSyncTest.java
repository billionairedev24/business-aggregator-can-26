package ca.northline.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.payments.api.CustomerCases;
import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.api.EscrowLifecycle;
import ca.northline.payments.api.PaymentAuthorizations;
import ca.northline.payments.api.TaxCalculations;
import ca.northline.payments.api.TaxSummary;
import ca.northline.payments.application.PaymentsJobs;
import ca.northline.payments.domain.CanadianTax;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.RuleViolation;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * S-21: tax quoted at checkout (the local fake's fixed Canadian rates), each captured sale and each refund reported
 * once as a Stripe Tax transaction / reversal, {@code payments.tax_jurisdiction_totals} filled per merchant,
 * jurisdiction and quarter (what Stripe &amp; compliance shows), refunds giving the GST back through the ledger, and the
 * reconciliation (job + staff endpoint).
 */
@Import(PaymentsFixture.class)
class StripeTaxSyncTest extends IntegrationTest {

    @Autowired
    PaymentsFixture fx;

    @Autowired
    TaxCalculations taxes;

    @Autowired
    PaymentAuthorizations checkout;

    @Autowired
    EscrowLifecycle escrows;

    @Autowired
    CustomerCases customers;

    @Autowired
    PaymentsJobs jobs;

    @Autowired
    TaxSummary summary;

    @Autowired
    JdbcClient jdbc;

    private static final String QUARTER = CanadianTax.period(Instant.now(), ZoneId.of("America/Edmonton"));

    /** A job paid at checkout with a tax quote for {@code province}, held and completed (captured). */
    private record Sale(String merchantId, String escrowId, String bookingId, TaxCalculations.Quote quote) {}

    private Sale sale(PaymentsFixture.Shop shop, String province, long amountCents) {
        var quote = taxes.calculate(
                new TaxCalculations.Request(shop.merchantId(), EscrowKind.SERVICE, province, null, amountCents));
        var booking = Ids.next();
        var customer = "cust-" + Ids.next();
        var started = checkout.start(new PaymentAuthorizations.Request(
                shop.merchantId(),
                "booking",
                booking,
                customer,
                amountCents,
                quote.taxCents(),
                "booking:" + booking,
                null,
                quote.calculationId()));
        var escrowId = escrows.hold(new EscrowLifecycle.Hold(
                shop.merchantId(),
                EscrowKind.SERVICE,
                "booking",
                booking,
                amountCents,
                quote.taxCents(),
                customer,
                "D. Kowalski",
                "Brake pads",
                null,
                null,
                "Brake pads",
                "search",
                started.paymentIntent(),
                Instant.now()));
        escrows.fulfilled("booking", booking, Instant.now());
        return new Sale(shop.merchantId(), escrowId, booking, quote);
    }

    private record Tx(
            String kind, String state, String jurisdiction, long amount, long tax, String stripe, int attempts) {}

    private Tx tx(String reference) {
        return jdbc.sql("""
                        select kind, state, jurisdiction, amount_cents, tax_cents, coalesce(stripe_transaction, ''), attempts
                          from payments.tax_transactions where reference = ?""")
                .params(reference)
                .query((rs, _) -> new Tx(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getLong(4),
                        rs.getLong(5),
                        rs.getString(6),
                        rs.getInt(7)))
                .single();
    }

    /** The outbox listener reports it right after commit; the job is the retry. Either way it ends recorded. */
    private void awaitRecorded(String reference) {
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            jobs.syncTax();
            assertThat(tx(reference).state()).isEqualTo("recorded");
        });
    }

    private record Totals(long collected, long sales, long reversed, int count, String handling) {}

    private Totals totals(String merchantId, String jurisdiction) {
        return jdbc.sql("""
                        select r.collected_cents, coalesce(s.sales_tax_cents, 0), coalesce(s.reversed_tax_cents, 0),
                               coalesce(s.transaction_count, 0), r.handling
                          from payments.tax_jurisdiction_totals r
                          left join payments.tax_totals_sync s using (merchant_id, period, jurisdiction)
                         where r.merchant_id = ? and r.period = ? and r.jurisdiction = ?""")
                .params(merchantId, QUARTER, jurisdiction)
                .query((rs, _) ->
                        new Totals(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4), rs.getString(5)))
                .single();
    }

    @Nested
    class Calculation {

        @Test
        void fixedCanadianRates_perProvince() {
            var shop = fx.shop("provider", "master");
            var ab = taxes.calculate(
                    new TaxCalculations.Request(shop.merchantId(), EscrowKind.SERVICE, "ab", "t2p 1b5", 24_700));
            assertThat(ab.taxCents()).isEqualTo(1_235);
            assertThat(ab.jurisdiction()).isEqualTo("ab_gst");
            var bc = taxes.calculate(
                    new TaxCalculations.Request(shop.merchantId(), EscrowKind.GOODS, "BC", null, 10_000));
            assertThat(bc.lines())
                    .extracting(TaxCalculations.Line::taxType, TaxCalculations.Line::taxCents)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("gst", 500L),
                            org.assertj.core.groups.Tuple.tuple("pst", 700L));
            var qc = taxes.calculate(
                    new TaxCalculations.Request(shop.merchantId(), EscrowKind.FOOD, "QC", null, 10_000));
            assertThat(qc.taxCents()).isEqualTo(500 + 998);
            assertThat(qc.jurisdiction()).isEqualTo("qc_gst_qst");
            var on = taxes.calculate(
                    new TaxCalculations.Request(shop.merchantId(), EscrowKind.SERVICE, "ON", null, 10_000));
            assertThat(on.taxCents()).isEqualTo(1_300);
            // the postal code is never stored
            assertThat(jdbc.sql("select count(*) from payments.tax_calculations where breakdown::text ilike '%T2P%'")
                            .query(Long.class)
                            .single())
                    .isZero();
        }

        @Test
        void validationMessages() {
            var shop = fx.shop("provider", "master");
            assertThatThrownBy(() -> taxes.calculate(
                            new TaxCalculations.Request(shop.merchantId(), EscrowKind.SERVICE, "XX", null, 1_000)))
                    .isInstanceOf(RuleViolation.class)
                    .hasMessageContaining("Choose a Canadian province or territory.");
            assertThatThrownBy(() -> taxes.calculate(
                            new TaxCalculations.Request(shop.merchantId(), EscrowKind.SERVICE, "AB", "90210", 1_000)))
                    .isInstanceOf(RuleViolation.class)
                    .hasMessageContaining("Enter a Canadian postal code, like T2P 1B5.");
            assertThatThrownBy(() -> taxes.calculate(
                            new TaxCalculations.Request(shop.merchantId(), EscrowKind.SERVICE, "AB", null, 0)))
                    .isInstanceOf(RuleViolation.class)
                    .hasMessageContaining("Enter an amount.");
        }

        @Test
        void checkout_mustMatchTheQuote_andAQuotePricesOneLine() {
            var shop = fx.shop("provider", "master");
            var quote = taxes.calculate(
                    new TaxCalculations.Request(shop.merchantId(), EscrowKind.SERVICE, "AB", null, 10_000));
            var booking = Ids.next();
            assertThatThrownBy(() -> checkout.start(new PaymentAuthorizations.Request(
                            shop.merchantId(),
                            "booking",
                            booking,
                            "cust-1",
                            10_000,
                            400,
                            "booking:" + booking,
                            null,
                            quote.calculationId())))
                    .isInstanceOf(RuleViolation.class)
                    .hasMessageContaining("The tax doesn't match its calculation.");
            checkout.start(new PaymentAuthorizations.Request(
                    shop.merchantId(),
                    "booking",
                    booking,
                    "cust-1",
                    10_000,
                    500,
                    "booking:" + booking,
                    null,
                    quote.calculationId()));
            var other = Ids.next();
            assertThatThrownBy(() -> checkout.start(new PaymentAuthorizations.Request(
                            shop.merchantId(),
                            "booking",
                            other,
                            "cust-1",
                            10_000,
                            500,
                            "booking:" + other,
                            null,
                            quote.calculationId())))
                    .isInstanceOf(Conflict.class);
            var otherShop = fx.shop("provider", "master");
            assertThatThrownBy(() -> checkout.start(new PaymentAuthorizations.Request(
                            otherShop.merchantId(),
                            "booking",
                            Ids.next(),
                            "cust-1",
                            10_000,
                            500,
                            "booking:x",
                            null,
                            quote.calculationId())))
                    .isInstanceOf(RuleViolation.class)
                    .hasMessageContaining("Calculate the tax again.");
        }
    }

    @Test
    void capturedSale_isReportedOnce_andFillsTheQuarterlyTotals_shownOnCompliance() throws Exception {
        var shop = fx.shop("provider", "master");
        var first = sale(shop, "BC", 10_000);
        var second = sale(shop, "BC", 5_000);
        var ref = "sale_" + first.escrowId();
        awaitRecorded(ref);
        awaitRecorded("sale_" + second.escrowId());

        var t = tx(ref);
        assertThat(t.kind()).isEqualTo("sale");
        assertThat(t.jurisdiction()).isEqualTo("bc_gst_pst");
        assertThat(t.tax()).isEqualTo(1_200);
        assertThat(t.stripe()).startsWith("tax_local_");
        assertThat(totals(shop.merchantId(), "bc_gst_pst"))
                .isEqualTo(new Totals(1_800, 1_800, 0, 2, "remitted_by_northline"));

        // listener, job and reconciliation again: nothing is reported twice, the totals don't move
        jobs.syncTax();
        jobs.reconcileTax();
        assertThat(tx(ref).stripe()).isEqualTo(t.stripe());
        assertThat(totals(shop.merchantId(), "bc_gst_pst").collected()).isEqualTo(1_800);
        assertThat(jdbc.sql("select count(*) from payments.tax_transactions where escrow_id = ?")
                        .params(first.escrowId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);

        assertThat(summary.totals(shop.merchantId(), QUARTER))
                .contains(new TaxSummary.JurisdictionTotal("bc_gst_pst", 1_800, "remitted_by_northline"));
        mvc.perform(get("/api/v1/merchants/{id}/compliance", shop.merchantId()).with(TestJwt.member(shop.ownerId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tax[?(@.jurisdiction == 'bc_gst_pst')].collectedCents")
                        .value(1_800));
    }

    @Test
    void saleWithoutACheckoutQuote_isPricedAtCapture_inTheMerchantsProvince() {
        var shop = fx.shop("provider", "master");
        jdbc.sql("update merchants.merchants set province = 'ON' where id = ?")
                .params(shop.merchantId())
                .update();
        var booking = Ids.next();
        var escrowId = escrows.hold(new EscrowLifecycle.Hold(
                shop.merchantId(),
                EscrowKind.SERVICE,
                "booking",
                booking,
                10_000,
                1_300,
                "cust-" + Ids.next(),
                "D. Kowalski",
                "Tune-up",
                null,
                null,
                "Tune-up",
                null,
                "pi_" + Ids.next(),
                Instant.now()));
        escrows.fulfilled("booking", booking, Instant.now());
        awaitRecorded("sale_" + escrowId);
        assertThat(tx("sale_" + escrowId).jurisdiction()).isEqualTo("on_hst");
        assertThat(jdbc.sql("""
                        select count(*) from payments.tax_calculations c join payments.tax_transactions t
                            on t.calculation_id = c.id where t.reference = ? and c.tax_cents = 1300""").params("sale_" + escrowId).query(Long.class).single())
                .isEqualTo(1);
        assertThat(totals(shop.merchantId(), "on_hst").collected()).isEqualTo(1_300);
    }

    @Test
    void refund_givesTheGstBack_reversesItAtStripeTax_andLowersTheTotals() throws Exception {
        var shop = fx.shop("provider", "master");
        var sale = sale(shop, "AB", 24_700); // GST 1 235
        awaitRecorded("sale_" + sale.escrowId());
        jdbc.sql("update payments.escrows set release_at = now() - interval '1 second' where id = ?")
                .params(sale.escrowId())
                .update();
        jobs.releaseDueEscrows();

        var refundId = customers.requestRefund(sale.escrowId(), customerOf(sale), 12_350, "Half");
        mvc.perform(post("/api/v1/merchants/{id}/refunds/{r}/accept", shop.merchantId(), refundId)
                        .header("Idempotency-Key", Ids.next())
                        .with(TestJwt.member(shop.ownerId())))
                .andExpect(status().isOk());
        jobs.payRefundQueue();

        // half the amount → half the GST, to the card with the amount; tax_payable no longer owes it
        assertThat(jdbc.sql("select tax_cents from payments.refunds where id = ?")
                        .params(refundId)
                        .query(Long.class)
                        .single())
                .isEqualTo(618);
        assertThat(ledger(refundId, "tax_payable")).isEqualTo(-618);
        assertThat(ledger(refundId, "stripe_balance")).isEqualTo(12_350 + 618);
        assertThat(ledger(refundId, "merchant:" + shop.merchantId())).isEqualTo(-12_350);

        var reversal = "refund_" + refundId;
        awaitRecorded(reversal);
        var r = tx(reversal);
        assertThat(r.kind()).isEqualTo("reversal");
        assertThat(r.tax()).isEqualTo(618);
        assertThat(r.jurisdiction()).isEqualTo("ab_gst");
        assertThat(totals(shop.merchantId(), "ab_gst"))
                .isEqualTo(new Totals(617, 1_235, 618, 2, "remitted_by_northline"));
        // Stripe Tax split the flat reversal the same way
        assertThat(jdbc.sql("select stripe_tax_cents from payments.tax_transactions where reference = ?")
                        .params(reversal)
                        .query(Long.class)
                        .single())
                .isEqualTo(618);
    }

    private String customerOf(Sale sale) {
        return jdbc.sql("select customer_id from payments.escrows where id = ?")
                .params(sale.escrowId())
                .query(String.class)
                .single();
    }

    /** Net of the refund's postings on an account: credits − debits. */
    private long ledger(String refundId, String account) {
        return jdbc.sql("""
                        select coalesce(sum(credit_cents - debit_cents), 0) from payments.ledger_entries
                         where ref_type = 'refund' and ref_id = ? and account = ?""").params(refundId, account).query(Long.class).single();
    }

    @Test
    void rowsWrittenBeforeTheSync_keepTheirAmountAsABase() {
        var shop = fx.shop("provider", "master");
        jdbc.sql("""
                        insert into payments.tax_jurisdiction_totals (merchant_id, period, jurisdiction, collected_cents, handling)
                        values (?, ?, 'ab_gst', 189240, 'remitted_by_northline'),
                               (?, ?, 'platform_fee_gst', 6140, 'charged_on_invoice')""")
                .params(shop.merchantId(), QUARTER, shop.merchantId(), QUARTER)
                .update();
        var sale = sale(shop, "AB", 10_000);
        awaitRecorded("sale_" + sale.escrowId());
        jobs.reconcileTax();
        jobs.reconcileTax();
        assertThat(totals(shop.merchantId(), "ab_gst").collected()).isEqualTo(189_240 + 500);
        assertThat(totals(shop.merchantId(), "platform_fee_gst"))
                .isEqualTo(new Totals(6_140, 0, 0, 0, "charged_on_invoice"));
    }

    @Test
    void aReportThatFails_isRecordedAndRetried_notLost() {
        var shop = fx.shop("provider", "master");
        var sale = sale(shop, "AB", 10_000);
        awaitRecorded("sale_" + sale.escrowId());
        // a reversal whose sale Stripe Tax doesn't know: the adapter refuses it
        jdbc.sql("update payments.tax_transactions set stripe_transaction = 'tax_unknown' where reference = ?")
                .params("sale_" + sale.escrowId())
                .update();
        var reference = "refund_" + Ids.next();
        jdbc.sql("""
                        insert into payments.tax_transactions (id, reference, kind, merchant_id, escrow_id, escrow_kind,
                               original_reference, province, jurisdiction, amount_cents, tax_cents, period, occurred_at,
                               state, created_at)
                        values (?, ?, 'reversal', ?, ?, 'service', ?, 'AB', 'ab_gst', 1000, 50, ?, now(), 'pending', now())""")
                .params(Ids.next(), reference, shop.merchantId(), sale.escrowId(), "sale_" + sale.escrowId(), QUARTER)
                .update();
        jobs.syncTax();
        var failed = tx(reference);
        assertThat(failed.state()).isEqualTo("failed");
        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(jdbc.sql("select error from payments.tax_transactions where reference = ?")
                        .params(reference)
                        .query(String.class)
                        .single())
                .contains("tax_unknown");
        assertThat(totals(shop.merchantId(), "ab_gst").reversed()).isZero();
        jobs.syncTax();
        assertThat(tx(reference).attempts()).isEqualTo(2);
    }

    @Nested
    class Reconciliation {

        @Test
        void staffRunsIt_forAQuarter() throws Exception {
            var shop = fx.shop("provider", "master");
            var sale = sale(shop, "SK", 10_000);
            awaitRecorded("sale_" + sale.escrowId());
            mvc.perform(post("/api/v1/console/payments/tax-reconciliations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"period\":\"" + QUARTER + "\"}")
                            .with(TestJwt.staff(Ids.next())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.period").value(QUARTER))
                    .andExpect(jsonPath("$.mismatched").isNumber())
                    .andExpect(jsonPath("$.rows").isNumber());
            assertThat(jdbc.sql("select stripe_tax_cents from payments.tax_transactions where reference = ?")
                            .params("sale_" + sale.escrowId())
                            .query(Long.class)
                            .single())
                    .isEqualTo(1_100);
            assertThat(totals(shop.merchantId(), "sk_gst_pst").collected()).isEqualTo(1_100);
        }

        @Test
        void currentQuarter_whenNoneGiven() throws Exception {
            mvc.perform(post("/api/v1/console/payments/tax-reconciliations").with(TestJwt.staff(Ids.next())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.period").value(QUARTER));
        }

        @Test
        void aBadPeriod_is422() throws Exception {
            mvc.perform(post("/api/v1/console/payments/tax-reconciliations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"period\":\"Q3 2026\"}")
                            .with(TestJwt.staff(Ids.next())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("period"))
                    .andExpect(jsonPath("$.errors[0].message").value("Use a quarter like 2026-Q3."));
        }

        @Test
        void merchantsAndSingleFactorStaff_areRefused() throws Exception {
            var shop = fx.shop("provider", "master");
            mvc.perform(post("/api/v1/console/payments/tax-reconciliations").with(TestJwt.member(shop.ownerId())))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/console/payments/tax-reconciliations").with(TestJwt.staffWithoutMfa(Ids.next())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("mfa_required"));
        }
    }
}
