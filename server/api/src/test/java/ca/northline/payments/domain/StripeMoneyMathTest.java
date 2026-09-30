package ca.northline.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Amounts, fees and transfers of separate charges and transfers (CAD cents), and the authorization window. */
class StripeMoneyMathTest {

    @Test
    void takeRate_isTheApplicationFee_theMerchantGetsTheRest_taxStaysWithNorthline() {
        // $247.00 job at Master 9 %: fee $22.23, transfer $224.77; the customer paid $247.00 + GST
        var fee = Fees.percentOf(24_700, 900);
        assertThat(fee).isEqualTo(2_223);
        assertThat(Fees.transferCents(24_700, fee)).isEqualTo(22_477);
        // half-up to the cent: 12 % of $0.05 = 0.6¢ → 1¢; 15 % of $0.03 = 0.45¢ → 0¢
        assertThat(Fees.percentOf(5, 1200)).isEqualTo(1);
        assertThat(Fees.percentOf(3, 1500)).isZero();
        assertThat(Fees.transferCents(100, 0)).isEqualTo(100);
        assertThat(Fees.transferCents(100, 100)).isZero();
        assertThatThrownBy(() -> Fees.transferCents(100, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Fees.transferCents(100, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void instantPayoutFee_isOnePercent_withAFiftyCentMinimum() {
        assertThat(Fees.instantFee(100)).isEqualTo(50);
        assertThat(Fees.instantFee(5_000)).isEqualTo(50);
        assertThat(Fees.instantFee(5_049)).isEqualTo(50);
        assertThat(Fees.instantFee(5_050)).isEqualTo(51); // 50.5¢ rounds half-up
        assertThat(Fees.instantFee(82_260)).isEqualTo(823);
    }

    @Test
    void transferReversal_isTheRefund_cappedAtWhatIsStillTransferred() {
        // $160 job at 9 %: transferred $145.60
        assertThat(Fees.transferReversalCents(8_000, 14_560, 0)).isEqualTo(8_000);
        assertThat(Fees.transferReversalCents(16_000, 14_560, 0)).isEqualTo(14_560);
        assertThat(Fees.transferReversalCents(8_000, 14_560, 8_000)).isEqualTo(6_560);
        assertThat(Fees.transferReversalCents(8_000, 14_560, 14_560)).isZero();
        assertThat(Fees.transferReversalCents(0, 14_560, 0)).isZero();
    }

    @Test
    void authorizationWindow_renews36hBeforeTheHoldLapses_andRetriesEvery12h() {
        var authorized = Instant.parse("2026-10-01T15:00:00Z");
        var lapses = authorized.plus(Duration.ofDays(7));
        assertThat(AuthorizationWindow.lapsesAt(null, authorized)).isEqualTo(lapses);
        var captureBefore = Instant.parse("2026-10-07T20:00:00Z");
        assertThat(AuthorizationWindow.lapsesAt(captureBefore, authorized)).isEqualTo(captureBefore);
        assertThat(AuthorizationWindow.renewFrom(null, authorized)).isEqualTo(lapses.minus(Duration.ofHours(36)));

        var early = lapses.minus(Duration.ofHours(37));
        var due = lapses.minus(Duration.ofHours(35));
        assertThat(AuthorizationWindow.renewalDue(null, authorized, null, early))
                .isFalse();
        assertThat(AuthorizationWindow.renewalDue(null, authorized, null, due)).isTrue();
        assertThat(AuthorizationWindow.renewalDue(null, authorized, null, lapses))
                .isFalse(); // too late: lapsed
        assertThat(AuthorizationWindow.renewalDue(null, authorized, due.minus(Duration.ofHours(1)), due))
                .isFalse();
        assertThat(AuthorizationWindow.renewalDue(null, authorized, due.minus(Duration.ofHours(12)), due))
                .isTrue();
    }
}
