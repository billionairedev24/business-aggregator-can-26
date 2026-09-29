package ca.northline.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.payments.api.EscrowKind;
import ca.northline.shared.RuleViolation;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/** Fees, payout schedules and release rules — plain domain tests. */
class PayoutRulesTest {

    private static java.time.Instant edmonton(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(Zones.EDMONTON).toInstant();
    }

    private static String local(java.time.Instant instant) {
        return ZonedDateTime.ofInstant(instant, Zones.EDMONTON)
                .toLocalDateTime()
                .toString();
    }

    @Test
    void instantFee_isOnePercent_withAFiftyCentMinimum() {
        assertThat(Fees.instantFee(82_260)).isEqualTo(823);
        assertThat(Fees.instantFee(1_000)).isEqualTo(50);
        assertThat(Fees.percentOf(24_700, 900)).isEqualTo(2_223);
        assertThat(Fees.percentOf(7_900, 900)).isEqualTo(711);
    }

    @Test
    void takeRates() {
        assertThat(Tier.MASTER.takeRateBps()).isEqualTo(900);
        assertThat(Tier.TRUSTED.takeRateBps()).isEqualTo(1200);
        assertThat(Tier.REGISTERED.takeRateBps()).isEqualTo(1500);
        assertThat(Tier.of(null)).isEqualTo(Tier.REGISTERED);
        assertThat(Tier.of("master")).isEqualTo(Tier.MASTER);
    }

    @Test
    void weekly_nextFridayAtNine() {
        var friday = PayoutSchedule.DEFAULT;
        // Tuesday Sep 8 2026, 10:00 → Friday Sep 11, 09:00
        assertThat(local(friday.nextAfter(edmonton("2026-09-08T10:00"), null).orElseThrow()))
                .isEqualTo("2026-09-11T09:00");
        // Friday 09:30, after the run → next Friday
        assertThat(local(friday.nextAfter(edmonton("2026-09-11T09:30"), null).orElseThrow()))
                .isEqualTo("2026-09-18T09:00");
        // Monday Sep 14 as in the design's preview
        var monday = new PayoutSchedule(PayoutSchedule.Frequency.WEEKLY, 1, null, PayoutSchedule.Reserve.NONE);
        assertThat(local(monday.nextAfter(edmonton("2026-09-08T10:00"), null).orElseThrow()))
                .isEqualTo("2026-09-14T09:00");
    }

    @Test
    void bankChangeHold_skipsPayoutDaysInsideIt() {
        var friday = PayoutSchedule.DEFAULT;
        // hold ends Friday 14:14 → that Friday's 09:00 run is skipped
        assertThat(local(friday.nextAfter(edmonton("2026-09-10T14:14"), edmonton("2026-09-11T14:14"))
                        .orElseThrow()))
                .isEqualTo("2026-09-18T09:00");
    }

    @Test
    void daily_monthly_manual() {
        var daily = new PayoutSchedule(PayoutSchedule.Frequency.DAILY, null, null, PayoutSchedule.Reserve.NONE);
        assertThat(local(daily.nextAfter(edmonton("2026-09-11T10:00"), null).orElseThrow()))
                .isEqualTo("2026-09-14T09:00");
        var first = new PayoutSchedule(
                PayoutSchedule.Frequency.MONTHLY,
                null,
                PayoutSchedule.MonthlyAnchor.FIRST,
                PayoutSchedule.Reserve.NONE);
        assertThat(local(first.nextAfter(edmonton("2026-09-08T10:00"), null).orElseThrow()))
                .isEqualTo("2026-10-01T09:00");
        var last = new PayoutSchedule(
                PayoutSchedule.Frequency.MONTHLY, null, PayoutSchedule.MonthlyAnchor.LAST, PayoutSchedule.Reserve.NONE);
        assertThat(local(last.nextAfter(edmonton("2026-02-10T10:00"), null).orElseThrow()))
                .isEqualTo("2026-02-28T09:00");
        var manual = new PayoutSchedule(PayoutSchedule.Frequency.MANUAL, 3, null, PayoutSchedule.Reserve.NONE);
        assertThat(manual.nextAfter(edmonton("2026-09-08T10:00"), null)).isEmpty();
        assertThat(manual.weekday()).isNull();
    }

    @Test
    void scheduleNeedsItsDay() {
        assertThatThrownBy(() ->
                        new PayoutSchedule(PayoutSchedule.Frequency.WEEKLY, null, null, PayoutSchedule.Reserve.NONE))
                .isInstanceOf(RuleViolation.class)
                .hasMessage("Choose a day of the week.");
        assertThatThrownBy(() ->
                        new PayoutSchedule(PayoutSchedule.Frequency.MONTHLY, null, null, PayoutSchedule.Reserve.NONE))
                .hasMessage("Choose a day of the month.");
    }

    @Test
    void reserve() {
        assertThat(PayoutSchedule.Reserve.KEEP_500.heldBack(82_260)).isEqualTo(50_000);
        assertThat(PayoutSchedule.Reserve.KEEP_500.heldBack(20_000)).isEqualTo(20_000);
        assertThat(PayoutSchedule.Reserve.PERCENT_10.heldBack(82_260)).isEqualTo(8_226);
        assertThat(PayoutSchedule.Reserve.NONE.heldBack(82_260)).isZero();
    }

    @Test
    void releaseRules() {
        var done = edmonton("2026-09-06T14:00");
        assertThat(Duration.between(done, EscrowKind.SERVICE.releaseAt(done))).isEqualTo(Duration.ofHours(48));
        assertThat(Duration.between(done, EscrowKind.GOODS.releaseAt(done))).isEqualTo(Duration.ofDays(7));
        assertThat(EscrowKind.FOOD.releaseAt(done)).isEqualTo(done);
    }

    @Test
    void refundUnder25_isAutoApprovedAfter48h_otherwiseAgentAfter24h() {
        var escrow = Escrow.builder()
                .id("e1")
                .paymentIntentId("pi")
                .refType("order_line")
                .refId("r1")
                .merchantId("m1")
                .kind(EscrowKind.GOODS)
                .amountCents(3_800)
                .feeCents(342)
                .takeRateBps(900)
                .taxCents(190)
                .label("Wiper blades ×2")
                .occurredAt(edmonton("2026-09-01T10:00"))
                .state(EscrowState.RELEASED)
                .createdAt(edmonton("2026-09-01T10:00"))
                .build();
        var now = edmonton("2026-09-08T10:00");
        var small = Refund.requested("RF-1", escrow, 1_900, "One blade wrong size", now);
        assertThat(small.isAuto()).isTrue();
        assertThat(small.lapse(now.plus(Duration.ofHours(47)))).isFalse();
        assertThat(small.lapse(now.plus(Duration.ofHours(48)))).isTrue();
        assertThat(small.getState()).isEqualTo(Refund.State.APPROVED);
        var big = Refund.requested("RF-2", escrow, 3_800, "Both wrong", now);
        assertThat(big.lapse(now.plus(Duration.ofHours(24)))).isTrue();
        assertThat(big.getState()).isEqualTo(Refund.State.AGENT_REVIEW);
        assertThatThrownBy(() -> Refund.requested("RF-3", escrow, 3_801, "Too much", now))
                .isInstanceOf(RuleViolation.class);
    }
}
