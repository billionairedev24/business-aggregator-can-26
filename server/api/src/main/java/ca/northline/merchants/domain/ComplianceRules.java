package ca.northline.merchants.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Stripe &amp; compliance rules: "Anything expiring shows here 30 days ahead; an expired requirement pauses instant book"
 * — after a grace period (docs/DECISIONS.md, same value the dashboard uses). Registry checks re-run monthly.
 */
public final class ComplianceRules {
    private ComplianceRules() {}

    public static final Duration DUE_SOON = Duration.ofDays(30);
    public static final Duration GRACE = Duration.ofDays(15);

    /** The current version of the platform obligations merchants accept (Business Terms Part B). */
    public static final String OBLIGATIONS_VERSION = "2.3";

    public static final String DOCUMENT_REQUIRED = "Choose a file to upload.";

    /** Checks shown under Stripe Connect (identity, bank) or never renewed (second factor, kitchen visit). */
    public static final Set<CheckType> NOT_IN_LEDGER =
            Set.of(CheckType.KYC, CheckType.BANK, CheckType.MFA, CheckType.SITE_VISIT);

    /** Kitchens' food-safety checks: the sidebar badge reads "AHS" when one of them needs the owner. */
    public static final Set<CheckType> FOOD_SAFETY =
            Set.of(CheckType.AHS_PERMIT, CheckType.FOOD_CERT, CheckType.INSPECTION);

    /** A verified row past its expiry is expired, whether or not a job has flipped the stored status yet. */
    public static VerificationStatus effective(VerificationStatus stored, @Nullable Instant expiresAt, Instant now) {
        if (stored == VerificationStatus.VERIFIED && expiresAt != null && !expiresAt.isAfter(now)) {
            return VerificationStatus.EXPIRED;
        }
        return stored;
    }

    /** Needs the owner now: expired, rejected or still to do. */
    public static boolean due(VerificationStatus effective) {
        return effective == VerificationStatus.EXPIRED
                || effective == VerificationStatus.REJECTED
                || effective == VerificationStatus.TODO;
    }

    /** Verified but expiring within 30 days. */
    public static boolean dueSoon(VerificationStatus effective, @Nullable Instant expiresAt, Instant now) {
        return effective == VerificationStatus.VERIFIED && expiresAt != null && expiresAt.isBefore(now.plus(DUE_SOON));
    }

    /** When instant book / ordering pauses for an expired requirement. */
    public static @Nullable Instant pausesAt(VerificationStatus effective, @Nullable Instant expiresAt) {
        return effective == VerificationStatus.EXPIRED && expiresAt != null ? expiresAt.plus(GRACE) : null;
    }
}
