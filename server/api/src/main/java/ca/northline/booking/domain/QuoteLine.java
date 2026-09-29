package ca.northline.booking.domain;

import ca.northline.booking.domain.QuoteEnums.LineKind;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.jspecify.annotations.Nullable;

/**
 * One line the customer sees. {@code unitCents} is the amount entered per unit (always ≥ 0); the line amount is
 * {@code qty × unit}, negative for discounts. Rules are checked by {@link QuoteContent}, which knows the line's index.
 */
public record QuoteLine(
        LineKind kind, String description, @Nullable String note, BigDecimal qty, long unitCents, boolean taxable) {

    public QuoteLine {
        description = description.strip();
        note = note == null || note.isBlank() ? null : note.strip();
    }

    /** {@code qty × unit}, rounded half-up to the cent; negative for discounts (V016 {@code chk_line_amount}). */
    public long amountCents() {
        long amount = qty.multiply(BigDecimal.valueOf(unitCents))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
        return kind == LineKind.DISCOUNT ? -amount : amount;
    }
}
