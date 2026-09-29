package ca.northline.payments.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Fee arithmetic in cents, half-up to the cent. */
public final class Fees {

    /** Instant payouts: "Fee · 1% (min $0.50)" (design 02 Payouts). */
    public static final int INSTANT_FEE_BPS = 100;

    public static final long INSTANT_MIN_FEE_CENTS = 50;

    /** Smallest instant payout (design: the amount must be ≥ $1). */
    public static final long INSTANT_MIN_AMOUNT_CENTS = 100;

    private Fees() {}

    public static long percentOf(long cents, int bps) {
        return BigDecimal.valueOf(cents)
                .multiply(BigDecimal.valueOf(bps))
                .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    public static long instantFee(long amountCents) {
        return Math.max(INSTANT_MIN_FEE_CENTS, percentOf(amountCents, INSTANT_FEE_BPS));
    }
}
