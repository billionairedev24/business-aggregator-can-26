package ca.northline.merchants.domain;

import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

/** One check of a merchant's verification checklist ({@code merchants.verifications} row). */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Verification {
    @EqualsAndHashCode.Include
    @ToString.Include
    private final String id;

    private final String merchantId;

    @ToString.Include
    private final String key;

    private final CheckType type;
    private final @Nullable String registry;

    @ToString.Include
    private VerificationStatus status;

    private @Nullable String reference;
    private @Nullable String documentId;
    private @Nullable Instant expiresAt;
    private int position;
    private Instant updatedAt;

    public static Verification open(String merchantId, CheckSpec spec, int position, Instant at) {
        return new Verification(
                Ids.next(),
                merchantId,
                spec.key(),
                spec.kind().type(),
                spec.registry(),
                VerificationStatus.TODO,
                null,
                null,
                null,
                position,
                at);
    }

    public CheckKind kind() {
        return CheckKind.ofKey(key).orElseThrow(() -> new IllegalStateException("Unknown check " + key));
    }

    public void moveTo(int newPosition) {
        position = newPosition;
    }

    /** Evidence handed in; a human (or a later registry run) confirms it. */
    public void submit(
            @Nullable String newReference, @Nullable String newDocumentId, @Nullable Instant expires, Instant at) {
        requireOpen();
        status = VerificationStatus.SUBMITTED;
        reference = newReference;
        documentId = newDocumentId;
        expiresAt = expires;
        updatedAt = at;
    }

    /** Confirmed immediately (registry match, Stripe KYC passed, attestation signed, bank linked). */
    public void verify(@Nullable String newReference, Instant at) {
        requireOpen();
        status = VerificationStatus.VERIFIED;
        reference = newReference;
        updatedAt = at;
    }

    /**
     * Rows whose state follows other records rather than one piece of evidence (the {@code kyc} row follows the
     * owners' Stripe Identity checks): may move in any direction, including back from verified. Returns whether it
     * changed.
     */
    public boolean follow(VerificationStatus newStatus, @Nullable String newReference, Instant at) {
        if (status == newStatus && java.util.Objects.equals(reference, newReference)) {
            return false;
        }
        status = newStatus;
        reference = newReference;
        updatedAt = at;
        return true;
    }

    /** Approval by trust &amp; safety: every submitted item counts as verified. */
    public void confirm(Instant at) {
        if (status == VerificationStatus.SUBMITTED) {
            status = VerificationStatus.VERIFIED;
            updatedAt = at;
        }
    }

    private void requireOpen() {
        if (status == VerificationStatus.VERIFIED) {
            throw new Conflict("already_verified", "This check is already verified.");
        }
    }
}
