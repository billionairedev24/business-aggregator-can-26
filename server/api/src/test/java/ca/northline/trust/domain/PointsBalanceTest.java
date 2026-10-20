package ca.northline.trust.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** S-152: balances spend the first-to-expire points first, expire what is left of each credit, carry debts. */
class PointsBalanceTest {

    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    static Instant day(int d) {
        return T0.plus(Duration.ofDays(d));
    }

    static PointsBalance.Row credit(String id, long points, int on, @Nullable Integer expiresOn) {
        return new PointsBalance.Row(id, points, "earn", day(on), expiresOn == null ? null : day(expiresOn));
    }

    static PointsBalance.Row debit(String id, long points, int on) {
        return new PointsBalance.Row(id, -points, "redemption", day(on), null);
    }

    @Test
    void creditsMinusDebits_withoutExpiry() {
        var r = PointsBalance.of(List.of(credit("a", 500, 0, null), debit("b", 200, 1)), day(10));
        assertThat(r.available()).isEqualTo(300);
        assertThat(r.debt()).isZero();
        assertThat(r.expired()).isEmpty();
        assertThat(r.nextExpiry()).isNull();
    }

    @Test
    void spendingUsesTheSoonestToExpire_soLessExpires() {
        // 100 that expire on day 30, 100 that never do; 100 spent on day 5 comes out of the expiring ones
        var rows = List.of(credit("a", 100, 0, 30), credit("b", 100, 1, null), debit("c", 100, 5));
        var r = PointsBalance.of(rows, day(40));
        assertThat(r.available()).isEqualTo(100);
        assertThat(r.expired()).containsEntry("a", 0L);
    }

    @Test
    void whatIsLeftOfACreditExpiresAtItsDate() {
        var rows = List.of(credit("a", 300, 0, 30), debit("c", 100, 5));
        var before = PointsBalance.of(rows, day(29));
        assertThat(before.available()).isEqualTo(200);
        assertThat(before.nextExpiry()).isEqualTo(new PointsBalance.Expiring(day(30), 200));
        var after = PointsBalance.of(rows, day(31));
        assertThat(after.available()).isZero();
        assertThat(after.expired()).containsEntry("a", 200L);
    }

    @Test
    void aDebitAfterExpiry_cannotUseExpiredPoints_andBecomesADebtTheNextCreditPays() {
        var rows = List.of(credit("a", 100, 0, 10), debit("r", 80, 20), credit("b", 50, 25, null));
        var r = PointsBalance.of(rows, day(30));
        assertThat(r.expired()).containsEntry("a", 100L);
        assertThat(r.available()).isZero();
        assertThat(r.debt()).isEqualTo(30);
    }

    @Test
    void theJobsExpiryRowsAreIgnored_soAnExpiryNeverCountsTwice() {
        var rows = List.of(
                credit("a", 100, 0, 10),
                credit("b", 40, 1, null),
                new PointsBalance.Row("x", -100, PointsBalance.EXPIRY, day(11), null));
        assertThat(PointsBalance.of(rows, day(12)).available()).isEqualTo(40);
    }
}
