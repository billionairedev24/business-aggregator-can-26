package ca.northline.hire.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * S-153: what cancelling a booking now costs under the policy it was booked under. Inside the window ({@code now} at
 * or after {@code freeUntil}) the fee is {@code feeBps} of the booking's price before tax, never more than what its
 * hold covers ({@code heldCents}, the deposit of a deposit quote), and carries the booking's own tax rate (a
 * cancellation fee is consideration for the supply — K-series note in DECISIONS). Outside it, or without a policy, it
 * is free.
 *
 * @param late inside the window
 */
public record LateCancellation(boolean late, long feeCents, long feeTaxCents) {

    public static final LateCancellation FREE = new LateCancellation(false, 0, 0);

    /**
     * @param priceCents the booking's price before tax
     * @param taxCents the tax on that price
     * @param heldCents what the booking's hold covers before tax
     */
    public static LateCancellation of(
            @Nullable Integer feeBps,
            @Nullable Instant freeUntil,
            long priceCents,
            long taxCents,
            long heldCents,
            boolean paid,
            Instant now) {
        if (!paid || feeBps == null || feeBps <= 0 || freeUntil == null || now.isBefore(freeUntil)) {
            return FREE;
        }
        var fee = Math.min(
                heldCents,
                BigDecimal.valueOf(priceCents)
                        .multiply(BigDecimal.valueOf(feeBps))
                        .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP)
                        .longValueExact());
        var tax = priceCents <= 0
                ? 0
                : BigDecimal.valueOf(fee)
                        .multiply(BigDecimal.valueOf(taxCents))
                        .divide(BigDecimal.valueOf(priceCents), 0, RoundingMode.HALF_UP)
                        .longValueExact();
        return fee <= 0 ? new LateCancellation(true, 0, 0) : new LateCancellation(true, fee, tax);
    }

    public long totalCents() {
        return feeCents + feeTaxCents;
    }
}
