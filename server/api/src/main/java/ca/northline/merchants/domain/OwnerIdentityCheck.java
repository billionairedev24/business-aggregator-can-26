package ca.northline.merchants.domain;

import ca.northline.shared.CodedEnum;
import ca.northline.shared.Conflict;
import java.time.Instant;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

/**
 * One owner's Stripe Identity verification ({@code merchants.owner_identity_checks}): the current VerificationSession
 * and what Northline keeps of its result — status, Stripe's error code and whether the name / date of birth match.
 * Webhook updates are applied in Stripe's order ({@code created}); updates for a replaced session are ignored.
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class OwnerIdentityCheck {

    /** {@code last_error} when a Northline agent sends the owner back to Stripe (S-79; the Studio words it). */
    public static final String AGENT_REJECTED = "agent_rejected";

    /** How the owner reaches Stripe's hosted flow. */
    public enum Delivery implements CodedEnum {
        /** The signed-in owner is this principal and opens the flow in their browser. */
        SELF,
        /** Stripe's link is emailed to the owner. */
        EMAIL
    }

    /** A Stripe webhook's news about a session, with the match results when it is verified. */
    public record SessionUpdate(
            String sessionId,
            IdentitySessionState state,
            @Nullable String lastError,
            IdentityMatch nameMatch,
            IdentityMatch dobMatch,
            Instant stripeCreated) {}

    @EqualsAndHashCode.Include
    @ToString.Include
    private final String id;

    private final String merchantId;
    private final String principalId;
    private @Nullable String stripeSession;

    @ToString.Include
    private IdentityCheckStatus status;

    private @Nullable String lastError;
    private @Nullable IdentityMatch nameMatch;
    private @Nullable IdentityMatch dobMatch;
    private Delivery delivery;
    private @Nullable String email;
    private int attempts;
    private String requestedBy;
    private @Nullable Instant stripeUpdatedAt;
    private @Nullable Instant verifiedAt;
    private Instant updatedAt;

    // S-79: the agent who decided a review (or sent the owner back), when, and what they wrote
    private @Nullable String reviewedBy;

    private @Nullable Instant reviewedAt;
    private @Nullable String reviewNote;

    /** @param id chosen before the session is opened (it is the session's {@code client_reference_id}) */
    public static OwnerIdentityCheck start(
            String id,
            String merchantId,
            String principalId,
            String sessionId,
            Delivery delivery,
            @Nullable String email,
            String requestedBy,
            Instant at) {
        return new OwnerIdentityCheck(
                id,
                merchantId,
                principalId,
                sessionId,
                IdentityCheckStatus.PENDING,
                null,
                null,
                null,
                delivery,
                email,
                1,
                requestedBy,
                null,
                null,
                at,
                null,
                null,
                null);
    }

    /** Guards a new session: nothing to redo once verified, and wait while Stripe is checking or an agent reviews. */
    public void requireRestartable() {
        switch (status) {
            case VERIFIED -> throw new Conflict("identity_already_verified", "This owner is already verified.");
            case PROCESSING ->
                throw new Conflict("identity_processing", "Stripe is still checking this owner's documents.");
            case REVIEW -> throw new Conflict("identity_in_review", "A Northline agent is reviewing this owner.");
            default -> {}
        }
    }

    /** A new session replaces the current one (which the caller cancels at Stripe). */
    public void restart(
            String sessionId, Delivery newDelivery, @Nullable String newEmail, String requester, Instant at) {
        requireRestartable();
        stripeSession = sessionId;
        status = IdentityCheckStatus.PENDING;
        lastError = null;
        nameMatch = null;
        dobMatch = null;
        delivery = newDelivery;
        email = newEmail;
        attempts++;
        requestedBy = requester;
        updatedAt = at;
    }

    /**
     * Applies a webhook update. Returns false when it changes nothing: another (replaced) session, an event older than
     * the last one applied, or a final state ({@code verified}, {@code review}) that only an agent may change.
     */
    public boolean apply(SessionUpdate update, Instant now) {
        if (!update.sessionId().equals(stripeSession)
                || (stripeUpdatedAt != null && update.stripeCreated().isBefore(stripeUpdatedAt))
                || status == IdentityCheckStatus.VERIFIED
                || status == IdentityCheckStatus.REVIEW) {
            return false;
        }
        stripeUpdatedAt = update.stripeCreated();
        updatedAt = now;
        switch (update.state()) {
            case REQUIRES_INPUT -> {
                status = update.lastError() == null ? IdentityCheckStatus.PENDING : IdentityCheckStatus.RETRY;
                lastError = update.lastError();
            }
            case PROCESSING -> status = IdentityCheckStatus.PROCESSING;
            case CANCELED -> status = IdentityCheckStatus.CANCELED;
            default -> {
                nameMatch = update.nameMatch();
                dobMatch = update.dobMatch();
                lastError = null;
                var mismatch =
                        update.nameMatch() == IdentityMatch.MISMATCH || update.dobMatch() == IdentityMatch.MISMATCH;
                status = mismatch ? IdentityCheckStatus.REVIEW : IdentityCheckStatus.VERIFIED;
                verifiedAt = mismatch ? null : now;
            }
        }
        return true;
    }

    /**
     * S-79: a Northline agent decides a name / date of birth mismatch. Approve → verified (the agent checked the
     * person); reject → the owner verifies again with Stripe ({@link #AGENT_REJECTED}).
     */
    public void decideReview(boolean approve, String agentId, @Nullable String note, Instant at) {
        if (status != IdentityCheckStatus.REVIEW) {
            throw new Conflict("review_closed", "This review was already decided.");
        }
        status = approve ? IdentityCheckStatus.VERIFIED : IdentityCheckStatus.RETRY;
        lastError = approve ? null : AGENT_REJECTED;
        verifiedAt = approve ? at : null;
        reviewedBy = agentId;
        reviewedAt = at;
        reviewNote = note;
        updatedAt = at;
    }

    /**
     * S-79 "Request info" on identity: whatever Stripe said, the owner verifies again. Returns false when the owner
     * already has to (no session handed in).
     */
    public boolean sendBack(String agentId, Instant at) {
        if (!status.handedIn()) {
            return false;
        }
        status = IdentityCheckStatus.RETRY;
        lastError = AGENT_REJECTED;
        verifiedAt = null;
        reviewedBy = agentId;
        reviewedAt = at;
        updatedAt = at;
        return true;
    }

    /**
     * The checklist's {@code kyc} row from the {@code owners} who need verification and the checks started for them:
     * all verified → verified; all handed in (verified, processing, in review) → submitted; otherwise to do.
     */
    public static VerificationStatus rollup(int owners, List<IdentityCheckStatus> started) {
        if (owners == 0 || started.size() < owners) {
            return VerificationStatus.TODO;
        }
        if (started.stream().allMatch(s -> s == IdentityCheckStatus.VERIFIED)) {
            return VerificationStatus.VERIFIED;
        }
        return started.stream().allMatch(IdentityCheckStatus::handedIn)
                ? VerificationStatus.SUBMITTED
                : VerificationStatus.TODO;
    }
}
