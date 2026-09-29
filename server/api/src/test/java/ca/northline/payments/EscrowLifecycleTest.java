package ca.northline.payments;

import static ca.northline.payments.PaymentsFixture.hoursAgo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.payments.api.EarningsQuery;
import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.api.EscrowLifecycle;
import ca.northline.payments.api.EscrowReleased;
import ca.northline.payments.application.PaymentsJobs;
import ca.northline.shared.Ids;
import ca.northline.shared.NavBadgeContributor;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * Escrow release rules (services 48 h after completion, goods 7 days after delivery, food on handoff, sign-off at
 * once), take rate by tier, the balanced ledger and its append-only trigger, nav badges and the dashboard query.
 */
@Import(PaymentsFixture.class)
@RecordApplicationEvents
class EscrowLifecycleTest extends IntegrationTest {

    @Autowired
    PaymentsFixture fx;

    @Autowired
    EscrowLifecycle escrows;

    @Autowired
    PaymentsJobs jobs;

    @Autowired
    ApplicationEvents events;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    List<NavBadgeContributor> badges;

    @Autowired
    EarningsQuery earnings;

    private EscrowLifecycle.Hold hold(String merchantId, EscrowKind kind, long cents) {
        return new EscrowLifecycle.Hold(
                merchantId,
                kind,
                kind == EscrowKind.SERVICE ? "booking" : "order_line",
                Ids.next(),
                cents,
                Math.round(cents * 0.05),
                "cust-" + Ids.next(),
                "D. Kowalski",
                "Brake pads",
                kind == EscrowKind.SERVICE ? null : "NL-48190",
                null,
                "Brake pads (parts)",
                "search",
                "pi_" + Ids.next(),
                Instant.now());
    }

    private record Row(String state, Instant releaseAt, long fee, int bps) {}

    private Row row(String escrowId) {
        return jdbc.sql("select state, release_at, fee_cents, take_rate_bps from payments.escrows where id = ?")
                .params(escrowId)
                .query((rs, _) -> new Row(
                        rs.getString(1),
                        rs.getTimestamp(2) == null
                                ? Instant.EPOCH
                                : rs.getTimestamp(2).toInstant(),
                        rs.getLong(3),
                        rs.getInt(4)))
                .single();
    }

    @Test
    void takeRateFollowsTheTier_andHoldIsIdempotent() {
        var master = fx.shop("provider", "master");
        var trusted = fx.shop("seller", "trusted");
        var registered = fx.shop("provider", "registered");
        var h = hold(master.merchantId(), EscrowKind.SERVICE, 24_700);
        var id = escrows.hold(h);
        assertThat(escrows.hold(h)).isEqualTo(id);
        assertThat(row(id).fee()).isEqualTo(2_223);
        assertThat(row(id).bps()).isEqualTo(900);
        assertThat(row(escrows.hold(hold(trusted.merchantId(), EscrowKind.GOODS, 10_000)))
                        .bps())
                .isEqualTo(1200);
        assertThat(row(escrows.hold(hold(registered.merchantId(), EscrowKind.SERVICE, 10_000)))
                        .bps())
                .isEqualTo(1500);
    }

    @Test
    void service_releases48hAfterCompletion_goods7DaysAfterDelivery() {
        var shop = fx.shop("both", "master");
        var service = hold(shop.merchantId(), EscrowKind.SERVICE, 12_000);
        var goods = hold(shop.merchantId(), EscrowKind.GOODS, 3_800);
        var serviceId = escrows.hold(service);
        var goodsId = escrows.hold(goods);
        var completed = Instant.now().minus(Duration.ofHours(1)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        escrows.fulfilled("booking", service.refId(), completed);
        escrows.fulfilled("order_line", goods.refId(), completed);
        assertThat(Duration.between(completed, row(serviceId).releaseAt())).isEqualTo(Duration.ofHours(48));
        assertThat(Duration.between(completed, row(goodsId).releaseAt())).isEqualTo(Duration.ofDays(7));
        assertThat(row(serviceId).state()).isEqualTo("held");

        jdbc.sql("update payments.escrows set release_at = now() - interval '1 second' where id = ?")
                .params(serviceId)
                .update();
        jobs.releaseDueEscrows();
        assertThat(row(serviceId).state()).isEqualTo("released");
        assertThat(row(goodsId).state()).isEqualTo("held");
        assertThat(events.stream(EscrowReleased.class))
                .anyMatch(e -> e.aggregateId().equals(serviceId) && e.netCents() == 10_920 && e.feeCents() == 1_080);
        assertThat(fx.balance(shop.merchantId())).isEqualTo(10_920);
        assertThat(jdbc.sql("select count(*) from payments.transfers where escrow_id = ?")
                        .params(serviceId)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void food_releasesOnHandoff_andSignOffReleasesAtOnce() {
        var kitchen = fx.shop("kitchen", "trusted");
        var food = hold(kitchen.merchantId(), EscrowKind.FOOD, 4_250);
        var foodId = escrows.hold(food);
        escrows.fulfilled("order_line", food.refId(), Instant.now());
        assertThat(row(foodId).state()).isEqualTo("released");

        var provider = fx.shop("provider", "master");
        var job = hold(provider.merchantId(), EscrowKind.SERVICE, 7_900);
        var jobId = escrows.hold(job);
        escrows.fulfilled("booking", job.refId(), Instant.now());
        escrows.confirmed("booking", job.refId(), Instant.now());
        assertThat(row(jobId).state()).isEqualTo("released");
    }

    @Test
    void ledgerIsBalanced_andAppendOnly() {
        var shop = fx.shop("kitchen", "trusted");
        var food = hold(shop.merchantId(), EscrowKind.FOOD, 4_250);
        var id = escrows.hold(food);
        escrows.fulfilled("order_line", food.refId(), Instant.now());
        var sums = jdbc.sql("select sum(debit_cents), sum(credit_cents) from payments.ledger_entries where ref_id = ?")
                .params(id)
                .query((rs, _) -> new long[] {rs.getLong(1), rs.getLong(2)})
                .single();
        assertThat(sums[0]).isEqualTo(sums[1]).isPositive();
        assertThatThrownBy(() -> jdbc.sql("update payments.ledger_entries set credit_cents = 1 where ref_id = ?")
                        .params(id)
                        .update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("delete from payments.ledger_entries where ref_id = ?")
                        .params(id)
                        .update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("""
                                insert into payments.ledger_entries (id, account, debit_cents, credit_cents, ref_type, ref_id, at)
                                values (?, 'escrow', 5, 5, 'test', 'x', now())""").params(Ids.next()).update())
                .hasMessageContaining("ledger_entries_one_side");
    }

    @Test
    void navBadges_nextPayoutDayAndCasesWaiting() {
        var shop = fx.shop("provider", "master");
        var escrowId = fx.escrow(
                shop.merchantId(), "service", "held", 16_000, 900, "Pre-purchase", "A. Osei", hoursAgo(90), null);
        fx.dispute(shop.merchantId(), escrowId, 16_000, PaymentsFixture.caseNumber("DS"));
        var en = new NavBadgeContributor.Context(shop.merchantId(), shop.ownerId(), MerchantRole.OWNER, Locale.CANADA);
        var fr = new NavBadgeContributor.Context(
                shop.merchantId(), shop.ownerId(), MerchantRole.OWNER, Locale.CANADA_FRENCH);
        var payments = badges.stream()
                .filter(b -> b.getClass().getSimpleName().startsWith("PaymentsNavBadges"))
                .findFirst()
                .orElseThrow();
        assertThat(payments.badges(en)).containsEntry("payouts", "Fri").containsEntry("refunds", "1");
        assertThat(payments.badges(fr)).containsEntry("payouts", "ven.");
    }

    @Test
    void dashboardQuery_weeklyNetByKind_andMonth() {
        var shop = fx.shop("both", "master");
        fx.escrow(
                shop.merchantId(),
                "service",
                "released",
                12_000,
                900,
                "Diagnostic",
                "M. Tran",
                hoursAgo(50),
                hoursAgo(0));
        fx.escrow(
                shop.merchantId(),
                "goods",
                "released",
                3_800,
                900,
                "Wipers",
                "S. Bouchard",
                hoursAgo(200),
                hoursAgo(0));
        var weeks = earnings.weeklyNet(shop.merchantId(), 12);
        assertThat(weeks).hasSize(12);
        var current = weeks.getLast();
        assertThat(current.servicesCents()).isEqualTo(10_920);
        assertThat(current.partsCents()).isEqualTo(3_458);
        assertThat(earnings.monthNet(shop.merchantId()).netCents()).isGreaterThanOrEqualTo(0);
    }
}
