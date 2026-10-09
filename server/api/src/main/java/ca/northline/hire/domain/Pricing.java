package ca.northline.hire.domain;

import ca.northline.shared.RuleViolation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.jspecify.annotations.Nullable;

/**
 * What a booked service costs (S-55): the fixed price; hourly work = the rate × the estimated hours (design 06 "Estimated
 * 3.0 hours · $135.00 at $45/h"); a consultation is free ("nothing is held"). GST/HST on top, half-up, at the province's
 * rate. Quote-only services aren't booked this way.
 */
public record Pricing(long priceCents, long taxCents) {

    public static final String QUOTED = "This service is priced by quote — ask for a quote instead.";

    public boolean free() {
        return priceCents == 0;
    }

    public static Pricing of(
            ServiceKind kind, String pricingMode, @Nullable Long priceCents, @Nullable BigDecimal hours, int taxBps) {
        if (kind == ServiceKind.CONSULT) {
            return new Pricing(0, 0);
        }
        if ("quote".equals(pricingMode) || priceCents == null) {
            throw RuleViolation.of("serviceId", "quote", QUOTED);
        }
        long price =
                "hourly".equals(pricingMode) ? hourly(priceCents, hours == null ? BigDecimal.ONE : hours) : priceCents;
        return new Pricing(price, bps(price, taxBps));
    }

    /** The tax on an amount after a promo code (mobile gaps part 2): the same province rate, half-up. */
    public static long taxOn(long cents, int taxBps) {
        return bps(cents, taxBps);
    }

    public static long hourly(long rateCents, BigDecimal hours) {
        return BigDecimal.valueOf(rateCents)
                .multiply(hours)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    static long bps(long cents, int bps) {
        return BigDecimal.valueOf(cents)
                .multiply(BigDecimal.valueOf(bps))
                .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }
}
