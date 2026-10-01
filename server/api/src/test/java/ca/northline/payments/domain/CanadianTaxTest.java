package ca.northline.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.api.EscrowLifecycle;
import ca.northline.payments.domain.CanadianTax.Province;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** S-21: the fake's fixed rates, the Edmonton quarter, the refund's share of the tax and the balanced postings. */
class CanadianTaxTest {

    /** Test data: a business in a Mountain-time market. */
    static final java.time.ZoneId ZONE = java.time.ZoneId.of("America/Edmonton");

    @Test
    void ratesAndJurisdictions() {
        assertThat(CanadianTax.total(Province.AB.lines(24_700))).isEqualTo(1_235);
        assertThat(CanadianTax.total(Province.BC.lines(10_000))).isEqualTo(1_200);
        assertThat(CanadianTax.total(Province.MB.lines(10_000))).isEqualTo(1_200);
        assertThat(CanadianTax.total(Province.SK.lines(10_000))).isEqualTo(1_100);
        assertThat(CanadianTax.total(Province.ON.lines(10_000))).isEqualTo(1_300);
        assertThat(CanadianTax.total(Province.NS.lines(10_000))).isEqualTo(1_400);
        assertThat(CanadianTax.total(Province.NB.lines(10_000))).isEqualTo(1_500);
        // each tax rounds on its own: QST 9.975 % of $100.00 = 997.5 → 998
        assertThat(Province.QC.lines(10_000))
                .extracting(CanadianTax.Line::taxCents)
                .containsExactly(500L, 998L);
        assertThat(Province.AB.jurisdiction()).isEqualTo("ab_gst");
        assertThat(Province.BC.jurisdiction()).isEqualTo("bc_gst_pst");
        assertThat(Province.QC.jurisdiction()).isEqualTo("qc_gst_qst");
        assertThat(Province.of(" on ")).contains(Province.ON);
        assertThat(Province.of("XX")).isEmpty();
    }

    @Test
    void periodIsTheEdmontonQuarter() {
        // 2026-10-01T05:59Z is still Sep 30 in Edmonton (MDT, UTC−6)
        assertThat(CanadianTax.period(Instant.parse("2026-10-01T05:59:00Z"), ZONE))
                .isEqualTo("2026-Q3");
        assertThat(CanadianTax.period(Instant.parse("2026-10-01T06:00:00Z"), ZONE))
                .isEqualTo("2026-Q4");
        assertThat(CanadianTax.period(Instant.parse("2027-01-01T07:00:00Z"), ZONE))
                .isEqualTo("2027-Q1");
    }

    @Test
    void refundShare() {
        assertThat(CanadianTax.refundShare(12_350, 24_700, 1_235)).isEqualTo(618);
        assertThat(CanadianTax.refundShare(24_700, 24_700, 1_235)).isEqualTo(1_235);
        assertThat(CanadianTax.refundShare(30_000, 24_700, 1_235)).isEqualTo(1_235);
        assertThat(CanadianTax.refundShare(1_900, 3_800, 0)).isZero();
        assertThat(CanadianTax.refundShare(1, 3_800, 190)).isZero();
    }

    private static Escrow escrow() {
        return Escrow.hold(
                new EscrowLifecycle.Hold(
                        "PWM1",
                        EscrowKind.GOODS,
                        "order_line",
                        "L1",
                        3_800,
                        190,
                        "cust-1",
                        "P. Nguyen",
                        "Wiper blades",
                        "NL-1",
                        null,
                        null,
                        null,
                        "pi_1",
                        Instant.EPOCH),
                1_200,
                "PI1",
                Instant.EPOCH);
    }

    private static long net(java.util.List<LedgerEntry> entries, String account) {
        return entries.stream()
                .filter(e -> e.account().equals(account))
                .mapToLong(e -> e.creditCents() - e.debitCents())
                .sum();
    }

    @Test
    void refundPostings_giveTheTaxBack_andBalance() {
        var refund = Refund.requested("RF-1", escrow(), 1_900, "One blade", Instant.EPOCH);
        assertThat(refund.getTaxCents()).isEqualTo(95);
        assertThat(refund.cardCents()).isEqualTo(1_995);
        var entries = LedgerEntry.refunded(refund, true, Instant.EPOCH);
        assertThat(entries.stream()
                        .mapToLong(e -> e.debitCents() - e.creditCents())
                        .sum())
                .isZero();
        assertThat(net(entries, LedgerEntry.TAX_PAYABLE)).isEqualTo(-95);
        assertThat(net(entries, LedgerEntry.merchant("PWM1"))).isEqualTo(-1_900);
        assertThat(net(entries, LedgerEntry.STRIPE_BALANCE)).isEqualTo(1_995);
    }

    @Test
    void lostChargeback_reversesTheTaxPart_andNorthlineCarriesOnlyWhatIsLeft() {
        var entries = LedgerEntry.chargedBack(escrow(), "DS1", 3_800, 190, true, Instant.EPOCH);
        assertThat(entries.stream()
                        .mapToLong(e -> e.debitCents() - e.creditCents())
                        .sum())
                .isZero();
        assertThat(net(entries, LedgerEntry.TAX_PAYABLE)).isEqualTo(-190);
        assertThat(net(entries, LedgerEntry.REVENUE)).isZero();
        var more = LedgerEntry.chargedBack(escrow(), "DS2", 3_800, 250, true, Instant.EPOCH);
        assertThat(net(more, LedgerEntry.TAX_PAYABLE)).isEqualTo(-190);
        assertThat(net(more, LedgerEntry.REVENUE)).isEqualTo(-60);
    }
}
