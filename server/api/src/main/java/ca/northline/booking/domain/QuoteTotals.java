package ca.northline.booking.domain;

import ca.northline.booking.domain.QuoteEnums.LineKind;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Money of a quote (validation-rules.md § Quote › totals): subtotal = Σ lines (discounts negative) — the value the
 * V016 trigger re-checks —, tax = taxable lines × the region rate, total = subtotal + tax. The breakdown by kind is
 * what the composer and the customer see (Labour · Parts &amp; materials · Fees &amp; travel · Discount).
 */
public record QuoteTotals(
        long labourCents,
        long partsCents,
        long feesCents,
        long discountCents,
        long subtotalCents,
        int taxBps,
        long taxCents,
        long totalCents,
        long depositCents) {

    public static QuoteTotals of(QuoteContent content, int taxBps) {
        var lines = content.lines();
        long labour = sum(lines, LineKind.LABOUR);
        long parts = sum(lines, LineKind.PART);
        long fees = sum(lines, LineKind.FEE) + sum(lines, LineKind.TRAVEL);
        long discount = -sum(lines, LineKind.DISCOUNT);
        long subtotal = lines.stream().mapToLong(QuoteLine::amountCents).sum();
        long taxable = lines.stream()
                .filter(QuoteLine::taxable)
                .mapToLong(QuoteLine::amountCents)
                .sum();
        long tax = Math.max(0, bps(taxable, taxBps));
        long total = subtotal + tax;
        long deposit = switch (content.depositKind()) {
            case NONE -> 0;
            case PCT -> bps(total, content.depositBps() == null ? 0 : content.depositBps());
            case PARTS_UPFRONT -> {
                long partsTaxable = lines.stream()
                        .filter(l -> l.kind() == LineKind.PART && l.taxable())
                        .mapToLong(QuoteLine::amountCents)
                        .sum();
                yield Math.min(total, parts + bps(partsTaxable, taxBps));
            }
        };
        return new QuoteTotals(labour, parts, fees, discount, subtotal, taxBps, tax, total, deposit);
    }

    private static long sum(List<QuoteLine> lines, LineKind kind) {
        return lines.stream()
                .filter(l -> l.kind() == kind)
                .mapToLong(QuoteLine::amountCents)
                .sum();
    }

    private static long bps(long cents, int bps) {
        return BigDecimal.valueOf(cents)
                .multiply(BigDecimal.valueOf(bps))
                .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }
}
