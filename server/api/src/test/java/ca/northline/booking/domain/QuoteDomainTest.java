package ca.northline.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.booking.domain.QuoteEnums.DepositKind;
import ca.northline.booking.domain.QuoteEnums.LineKind;
import ca.northline.booking.domain.QuoteEnums.QuoteState;
import ca.northline.booking.domain.QuoteEnums.Warranty;
import ca.northline.shared.Conflict;
import ca.northline.shared.RuleViolation;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuoteDomainTest {

    static QuoteContent content(DepositKind deposit, Integer bps, QuoteLine... lines) {
        return new QuoteContent(List.of(lines), "Scope", null, null, 120, 72, Warranty.NONE, deposit, bps, List.of());
    }

    static QuoteLine line(LineKind kind, String qty, long unit) {
        return new QuoteLine(kind, "Line", null, new BigDecimal(qty), unit, true);
    }

    @Test
    void totalsByKindWithGst() {
        var t = QuoteTotals.of(
                content(
                        DepositKind.NONE,
                        null,
                        line(LineKind.LABOUR, "0.5", 6500),
                        line(LineKind.PART, "1", 24000),
                        line(LineKind.TRAVEL, "1", 2000),
                        line(LineKind.FEE, "1", 1200),
                        line(LineKind.DISCOUNT, "1", 1000)),
                500);
        assertThat(t.labourCents()).isEqualTo(3250);
        assertThat(t.feesCents()).isEqualTo(3200);
        assertThat(t.discountCents()).isEqualTo(1000);
        assertThat(t.subtotalCents()).isEqualTo(29450);
        assertThat(t.taxCents()).isEqualTo(1473);
        assertThat(t.totalCents()).isEqualTo(30923);
    }

    @Test
    void reviseSupersedesAndBumpsVersion_acceptChecksCustomerAndExpiry() {
        var now = Instant.parse("2026-09-08T15:00:00Z");
        var q = Quote.draft(
                "r", "m", "QT-1", 1, content(DepositKind.PCT, 2500, line(LineKind.LABOUR, "1", 10000)), 500, "u", now);
        q.send("u", now, null);
        assertThat(q.getValidUntil()).isEqualTo(now.plus(Duration.ofDays(3)));
        assertThat(q.getTotals().depositCents()).isEqualTo(2625);
        assertThatThrownBy(() -> q.redraft(q.getContent(), 500)).isInstanceOf(Conflict.class);

        var v2 = q.revise(content(DepositKind.NONE, null, line(LineKind.LABOUR, "1", 9000)), 500, "u", now);
        assertThat(q.getState()).isEqualTo(QuoteState.SUPERSEDED);
        assertThat(v2.getVersion()).isEqualTo(2);
        assertThat(v2.getRef()).isEqualTo("QT-1");
        v2.send("u", now, q.getId());

        assertThatThrownBy(() -> v2.accept("someone", "customer", now)).isInstanceOf(Conflict.class);
        assertThatThrownBy(() -> v2.accept("customer", "customer", now.plus(Duration.ofDays(4))))
                .hasMessageContaining("expired");
        var accepted = v2.accept("customer", "customer", now.plus(Duration.ofHours(1)));
        assertThat(accepted.totalCents()).isEqualTo(9450);
        assertThat(v2.getState()).isEqualTo(QuoteState.ACCEPTED);
    }

    @Test
    void reportsEveryBrokenRuleAtOnce() {
        assertThatThrownBy(() -> new QuoteContent(
                                List.of(new QuoteLine(LineKind.PART, " ", null, BigDecimal.ONE, 0, true)),
                                " ",
                                null,
                                null,
                                null,
                                72,
                                Warranty.NONE,
                                DepositKind.NONE,
                                null,
                                List.of())
                        .validate())
                .isInstanceOfSatisfying(
                        RuleViolation.class,
                        e -> assertThat(e.getViolations())
                                .extracting(RuleViolation.Violation::field)
                                .containsExactly("lines[0].description", "lines[0].unitCents", "scope"));
    }
}
