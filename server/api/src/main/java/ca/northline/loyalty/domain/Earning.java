package ca.northline.loyalty.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;

/**
 * S-152: what a completed sale earns. Points are per whole dollar of the merchant's amount the customer paid (after a
 * code and points, before tax; fees and tips never earn) at the region's rate, then Plus's multiplier. A refund takes
 * back its share, rounded up so a full refund takes everything.
 */
public final class Earning {

    private Earning() {}

    /** Points before Plus or a reward. */
    public static long base(long amountCents, int pointsPerDollar) {
        return amountCents <= 0 || pointsPerDollar <= 0 ? 0 : (amountCents / 100) * pointsPerDollar;
    }

    /** With Plus's multiplier ({@code 200} = 2×). */
    public static long withPlus(long basePoints, int multiplierPct) {
        return basePoints * multiplierPct / 100;
    }

    /** The share of {@code points} a refund of {@code refundedCents} out of {@code amountCents} takes back. */
    public static long reversal(long points, long refundedCents, long amountCents) {
        if (points <= 0 || refundedCents <= 0 || amountCents <= 0) {
            return 0;
        }
        if (refundedCents >= amountCents) {
            return points;
        }
        return Math.min(points, (points * refundedCents + amountCents - 1) / amountCents);
    }

    /** When points earned at {@code at} expire under a rule of {@code months} (null = never). */
    public static @Nullable Instant expiresAt(Instant at, @Nullable Integer months) {
        return months == null
                ? null
                : at.atOffset(ZoneOffset.UTC).plusMonths(months).toInstant();
    }
}
