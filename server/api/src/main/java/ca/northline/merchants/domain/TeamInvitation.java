package ca.northline.merchants.domain;

import ca.northline.shared.CodedEnum;
import ca.northline.shared.Conflict;
import ca.northline.shared.security.MerchantRole;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * An owner's invitation for someone to join the team ({@code merchants.member_invitations}). The invitee signs in with
 * their own account (or creates one) and accepts with the token from the link; only its hash is stored.
 */
public record TeamInvitation(
        String id,
        String merchantId,
        MerchantRole role,
        TeamRules.Contact contact,
        String invitedBy,
        Instant createdAt,
        Instant expiresAt,
        @Nullable Instant acceptedAt,
        @Nullable Instant revokedAt) {

    public enum State implements CodedEnum {
        PENDING,
        EXPIRED,
        ACCEPTED,
        REVOKED
    }

    public State state(Instant now) {
        if (acceptedAt != null) {
            return State.ACCEPTED;
        }
        if (revokedAt != null) {
            return State.REVOKED;
        }
        return now.isBefore(expiresAt) ? State.PENDING : State.EXPIRED;
    }

    /** Guards accepting: only a pending invitation can be used. */
    public void requireUsable(Instant now) {
        var refusal = switch (state(now)) {
            case PENDING -> null;
            case EXPIRED -> new Conflict("invitation_expired", "This invitation has expired. Ask for a new one.");
            case ACCEPTED -> new Conflict("invitation_used", "This invitation was already used.");
            case REVOKED -> new Conflict("invitation_revoked", "This invitation was withdrawn.");
        };
        if (refusal != null) {
            throw refusal;
        }
    }
}
