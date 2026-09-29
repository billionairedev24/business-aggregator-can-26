package ca.northline.booking.web;

import ca.northline.booking.domain.QuoteContent;
import ca.northline.booking.domain.QuoteEnums.LineKind;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/** One line of the composer: Item · Type · Qty · Amount (per unit, in cents). */
@PositiveUnlessDiscount
record QuoteLineBody(
        @NotNull(message = "Choose a type.") LineKind kind,

        @NotBlank(message = QuoteContent.LINE_DESCRIPTION_REQUIRED)
        @Size(max = QuoteContent.LINE_DESCRIPTION_MAX, message = QuoteContent.LINE_DESCRIPTION_TOO_LONG)
        String description,

        @Size(max = 160, message = QuoteContent.LINE_DESCRIPTION_TOO_LONG) @Nullable
        String note,

        @NotNull(message = QuoteContent.LINE_QTY_RANGE)
        @DecimalMin(value = "0.01", message = QuoteContent.LINE_QTY_RANGE)
        @Digits(integer = 6, fraction = 2, message = QuoteContent.LINE_QTY_RANGE)
        BigDecimal qty,

        @NotNull(message = QuoteContent.LINE_AMOUNT_REQUIRED)
        @PositiveOrZero(message = QuoteContent.LINE_AMOUNT_REQUIRED)
        Long unitCents,

        @Nullable Boolean taxable) {}
