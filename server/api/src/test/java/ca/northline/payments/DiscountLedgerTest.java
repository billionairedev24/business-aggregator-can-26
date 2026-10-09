package ca.northline.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ca.northline.payments.api.CustomerCases;
import ca.northline.payments.api.DisputeDecisions;
import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.api.EscrowLifecycle;
import ca.northline.payments.api.PointsReturned;
import ca.northline.payments.application.PaymentsJobs;
import ca.northline.shared.Ids;
import ca.northline.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * Mobile gaps part 2, money: who funds a promo code and what points pay, through the whole escrow life — hold, capture,
 * release, transfer, refunds — with every posting balanced. Northline-funded codes top the merchant up from
 * {@code promotions} at release (the merchant gets the full price less the fee on it); merchant-funded codes show in the
 * merchant's own account; points are Northline's money ({@code points_redeemed}) and go back to the wallet with refunds.
 */
@Import(PaymentsFixture.class)
@RecordApplicationEvents
class DiscountLedgerTest extends IntegrationTest {

    @Autowired
    PaymentsFixture fx;

    @Autowired
    EscrowLifecycle escrows;

    @Autowired
    PaymentsJobs jobs;

    @Autowired
    CustomerCases cases;

    @Autowired
    DisputeDecisions decisions;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEvents events;

    private record Held(String escrowId, String refId, String customerId) {}

    /** A booking held at {@code amount} (after the code) with 5 % tax. Master tier: 9 % take. */
    private Held hold(String merchantId, long amount, EscrowLifecycle.@Nullable Discount discount) {
        var refId = Ids.next();
        var customer = data.user("Dana Kowalski");
        var id = escrows.hold(new EscrowLifecycle.Hold(
                merchantId,
                EscrowKind.SERVICE,
                "booking",
                refId,
                amount,
                Math.round(amount * 0.05),
                customer,
                "D. Kowalski",
                "Brake inspection",
                null,
                null,
                "Brake inspection",
                "search",
                "pi_" + Ids.next(),
                Instant.now(),
                null,
                discount));
        return new Held(id, refId, customer);
    }

    /** Account → (debits, credits) of an escrow's own postings and its refunds'. */
    private Map<String, long[]> postings(String escrowId) {
        return jdbc
                .sql("""
                        select account, sum(debit_cents) as d, sum(credit_cents) as c from payments.ledger_entries
                         where (ref_type = 'escrow' and ref_id = :e)
                            or (ref_type = 'refund' and ref_id in (select id from payments.refunds where escrow_id = :e))
                         group by account""")
                .param("e", escrowId)
                .query((rs, _) -> Map.entry(rs.getString("account"), new long[] {rs.getLong("d"), rs.getLong("c")}))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static void balanced(Map<String, long[]> postings) {
        var debits = postings.values().stream().mapToLong(v -> v[0]).sum();
        var credits = postings.values().stream().mapToLong(v -> v[1]).sum();
        assertThat(debits).as("Σ debits = Σ credits").isEqualTo(credits);
    }

    private long transferred(String escrowId) {
        return jdbc.sql("select coalesce(sum(net_cents), 0) from payments.transfers where escrow_id = ?")
                .params(escrowId)
                .query(Long.class)
                .single();
    }

    @Test
    void aNorthlineFundedCode_topsTheMerchantUpToTheFullPriceAtRelease() {
        var shop = fx.shop("provider", "master");
        // $100 service, $20 off funded by Northline: the customer pays $80 + 5 % GST on $80
        var h = hold(shop.merchantId(), 8_000, new EscrowLifecycle.Discount(2_000, "northline", 0));
        var row = jdbc.sql("select fee_cents, discount_cents, discount_funded_by from payments.escrows where id = ?")
                .params(h.escrowId())
                .query((rs, _) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3)))
                .single();
        assertThat(row).containsExactly("900", "2000", "northline"); // 9 % of the full $100

        escrows.confirmed("booking", h.refId(), Instant.now());

        var p = postings(h.escrowId());
        balanced(p);
        assertThat(p.get("stripe_balance")[0]).isEqualTo(8_400); // the card: $80 + $4 GST
        assertThat(p.get("tax_payable")[1]).isEqualTo(400); // GST on the discounted $80
        assertThat(p.get("promotions")[0]).isEqualTo(2_000); // Northline's cost of the code
        assertThat(p.get("revenue")[1]).isEqualTo(900);
        assertThat(fx.balance(shop.merchantId())).isEqualTo(9_100); // $100 − 9 %, as without the code
        assertThat(transferred(h.escrowId())).isEqualTo(9_100);
    }

    @Test
    void aMerchantFundedCode_isTheMerchantsDiscount_feeOnWhatTheySold() {
        var shop = fx.shop("provider", "master");
        var h = hold(shop.merchantId(), 8_000, new EscrowLifecycle.Discount(2_000, "merchant", 0));
        escrows.confirmed("booking", h.refId(), Instant.now());

        var p = postings(h.escrowId());
        balanced(p);
        assertThat(p).doesNotContainKey("promotions");
        assertThat(p.get("revenue")[1]).isEqualTo(720); // 9 % of $80
        var merchant = p.get("merchant:" + shop.merchantId());
        assertThat(merchant[0]).isEqualTo(2_000); // the discount it funded
        assertThat(merchant[1]).isEqualTo(9_280); // the full price less the fee
        assertThat(fx.balance(shop.merchantId())).isEqualTo(7_280);
        assertThat(transferred(h.escrowId())).isEqualTo(7_280);
    }

    @Test
    void pointsPayPartOfTheCard_andAFullRefundGivesThemBack() {
        var shop = fx.shop("provider", "master");
        // $100 + $5 GST, $30 paid with points: the card is charged $75
        var h = hold(shop.merchantId(), 10_000, new EscrowLifecycle.Discount(0, null, 3_000));
        escrows.fulfilled("booking", h.refId(), Instant.now());
        var captured = postings(h.escrowId());
        balanced(captured);
        assertThat(captured.get("stripe_balance")[0]).isEqualTo(7_500);
        assertThat(captured.get("points_redeemed")[0]).isEqualTo(3_000);
        assertThat(captured.get("tax_payable")[1]).isEqualTo(500); // tax on the full $100: points pay like money

        var refund = cases.requestReview(h.escrowId(), h.customerId(), 10_000, "Didn't show up");
        decisions.decideRefund(refund, true, "agent-" + Ids.next());
        jobs.payRefundQueue();

        var after = postings(h.escrowId());
        balanced(after);
        assertThat(after.get("stripe_balance")[1]).isEqualTo(7_500); // the card gets back what it paid
        assertThat(after.get("points_redeemed")[1]).isEqualTo(3_000); // the points go back to the wallet
        assertThat(after.get("tax_payable")[0]).isEqualTo(500);
        assertThat(events.stream(PointsReturned.class))
                .anyMatch(e -> e.aggregateId().equals(refund)
                        && e.cents() == 3_000
                        && e.customerId().equals(h.customerId())
                        && e.escrowRefId().equals(h.refId()));
    }

    @Test
    void aPartialRefundAfterRelease_takesBackItsShareOfNorthlinesTopUp() {
        var shop = fx.shop("provider", "master");
        var h = hold(shop.merchantId(), 8_000, new EscrowLifecycle.Discount(2_000, "northline", 0));
        escrows.confirmed("booking", h.refId(), Instant.now());
        assertThat(fx.balance(shop.merchantId())).isEqualTo(9_100);

        // half of what the customer paid comes back: $40 + $2 GST to the card, $10 of the top-up back from the merchant
        var refund = cases.requestReview(h.escrowId(), h.customerId(), 4_000, "Half the job");
        decisions.decideRefund(refund, true, "agent-" + Ids.next());
        jobs.payRefundQueue();

        var p = postings(h.escrowId());
        balanced(p);
        assertThat(jdbc.sql("select promo_return_cents from payments.refunds where id = ?")
                        .params(refund)
                        .query(Long.class)
                        .single())
                .isEqualTo(1_000);
        assertThat(p.get("promotions")[1]).isEqualTo(1_000);
        assertThat(fx.balance(shop.merchantId())).isEqualTo(9_100 - 4_000 - 1_000);
        // the whole ledger of every account still balances
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> balanced(postings(h.escrowId())));
    }

    @Test
    void withoutAnyDiscount_nothingChanges() {
        var shop = fx.shop("provider", "master");
        var h = hold(shop.merchantId(), 10_000, null);
        escrows.confirmed("booking", h.refId(), Instant.now());
        var p = postings(h.escrowId());
        balanced(p);
        assertThat(p).doesNotContainKeys("promotions", "points_redeemed");
        assertThat(fx.balance(shop.merchantId())).isEqualTo(9_100);
    }
}
