package ca.northline.food.domain;

import ca.northline.shared.RuleViolation;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Price and saving of a combo. {@code referenceCents} = the items bought separately, counted at the cheapest eligible
 * item of every slot, so the advertised saving is never overstated.
 */
public record ComboPrice(long referenceCents, long priceCents) {

    public long savingCents() {
        return Math.max(0, referenceCents - priceCents);
    }

    public static ComboPrice of(long referenceCents, ComboPricing pricing, long fixedPriceCents, int discountBps) {
        var price = switch (pricing) {
            case FIXED -> fixedPriceCents;
            case PERCENT_OFF ->
                BigDecimal.valueOf(referenceCents)
                        .multiply(BigDecimal.valueOf(10_000L - discountBps))
                        .divide(BigDecimal.valueOf(10_000L), 0, RoundingMode.HALF_UP)
                        .longValueExact();
        };
        return new ComboPrice(referenceCents, price);
    }

    /** A combo must save something: 422 on {@code field} otherwise. */
    public ComboPrice requireSaving(String field) {
        if (priceCents >= referenceCents) {
            throw RuleViolation.of(field, "saving", KitchenMessages.COMBO_SAVING);
        }
        return this;
    }
}
