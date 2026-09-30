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

    /**
     * Separate charges and transfers: the customer pays {@code amount + tax} to Northline; the merchant's connected
     * account receives {@code amount − fee}; Northline keeps the fee (its application fee = the take rate) and the tax
     * (remitted to the CRA).
     */
    public static long transferCents(long amountCents, long feeCents) {
        if (feeCents < 0 || feeCents > amountCents) {
            throw new IllegalArgumentException("fee " + feeCents + " outside 0.." + amountCents);
        }
        return amountCents - feeCents;
    }

    /**
     * How much of a refund charged to the merchant is pulled back from their connected account: the refunded amount
     * (the ledger debits the merchant with all of it; Northline's fee is not refunded), capped at what is still
     * transferred. Whatever the cap leaves is a negative merchant balance, recovered from later releases.
     */
    public static long transferReversalCents(long refundCents, long transferredCents, long alreadyReversedCents) {
        return Math.max(0, Math.min(refundCents, transferredCents - alreadyReversedCents));
    }
}
