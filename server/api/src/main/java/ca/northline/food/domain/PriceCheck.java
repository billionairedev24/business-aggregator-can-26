package ca.northline.food.domain;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Menu price vetting (S-67, design 02c "prices within ±40 % of the cuisine median"): a dish priced more than
 * {@value #BAND_PCT} % above or below the median of comparable live dishes is an outlier, and stays off the menu until
 * the owner confirms that price. No median (too few dishes to compare) means no check.
 *
 * @param medianCents the comparable median, or null
 * @param confirmedCents the price the owner confirmed, or null
 */
public record PriceCheck(
        long priceCents,
        @Nullable Long medianCents,
        @Nullable Long confirmedCents) {

    public static final int BAND_PCT = 40;

    /** Outside ±40 % of the median and not the price the owner confirmed. */
    public boolean flagged() {
        return outlier() && !Objects.equals(confirmedCents, priceCents);
    }

    /** Outside ±40 % of the median (confirmed or not). */
    public boolean outlier() {
        var median = medianCents;
        if (median == null || median <= 0) {
            return false;
        }
        return priceCents * 100 > median * (100 + BAND_PCT) || priceCents * 100 < median * (100 - BAND_PCT);
    }

    /** How far from the median, in whole percent: +52 = 52 % above, -45 = 45 % below; 0 without a median. */
    public int deviationPct() {
        var median = medianCents;
        if (median == null || median <= 0) {
            return 0;
        }
        return (int) Math.round((priceCents - median) * 100.0 / median);
    }
}
