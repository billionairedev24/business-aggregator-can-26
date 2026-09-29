package ca.northline.merchants.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One row of "Licences, insurance &amp; policies" — a {@code merchants.verifications} row with its effective state.
 *
 * @param checkKey onboarding's checklist key ({@code licence:AMVIC}, {@code gst}); null for rows added after onboarding
 * @param label the name as shown, when one was recorded ("Liability insurance $2M · Intact")
 * @param status effective status (a verified row past its expiry is {@code expired})
 * @param pausesAt when instant book / ordering pauses because this expired
 */
public record ComplianceItem(
        String id,
        CheckType checkType,
        @Nullable String checkKey,
        @Nullable String registry,
        @Nullable String reference,
        @Nullable String label,
        VerificationStatus status,
        @Nullable Instant expiresAt,
        @Nullable Instant verifiedAt,
        @Nullable Instant submittedAt,
        @Nullable Instant pausesAt,
        boolean due,
        boolean dueSoon) {

    /** Stored row → item as of {@code now}. */
    public static ComplianceItem of(
            String id,
            CheckType type,
            @Nullable String checkKey,
            @Nullable String registry,
            @Nullable String reference,
            @Nullable String label,
            VerificationStatus stored,
            @Nullable Instant expiresAt,
            @Nullable Instant verifiedAt,
            @Nullable Instant submittedAt,
            Instant now) {
        var effective = ComplianceRules.effective(stored, expiresAt, now);
        return new ComplianceItem(
                id,
                type,
                checkKey,
                registry,
                reference,
                label,
                effective,
                expiresAt,
                verifiedAt,
                submittedAt,
                ComplianceRules.pausesAt(effective, expiresAt),
                ComplianceRules.due(effective),
                ComplianceRules.dueSoon(effective, expiresAt, now));
    }

    /** A new document can be handed in unless one is already waiting for review. */
    public boolean renewable() {
        return status != VerificationStatus.SUBMITTED;
    }
}
