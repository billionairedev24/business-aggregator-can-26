package ca.northline.merchants.domain;

import ca.northline.shared.CodedEnum;
import ca.northline.shared.Conflict;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * S-120: a staff member's invitation for a business to join a market's pilot ({@code merchants.pilot_invites}). The
 * link carries a random token; only its SHA-256 is stored, so the link is the proof. It expires, is used once, and a
 * new one for the same pilot business withdraws the last.
 */
public record PilotInvite(
        String id,
        String pilotId,
        String email,
        String sentBy,
        Instant createdAt,
        Instant expiresAt,
        @Nullable Instant acceptedAt,
        @Nullable Instant revokedAt) {

    /** How long a link works. */
    public static final Duration TTL = Duration.ofDays(14);

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

    /** Guards accepting: only a pending invite starts a business. */
    public void requireUsable(Instant now) {
        var refusal = switch (state(now)) {
            case PENDING -> null;
            case EXPIRED ->
                new Conflict("pilot_invite_expired", "This invite has expired. Ask Northline for a new one.");
            case ACCEPTED -> new Conflict("pilot_invite_used", "This invite was already used.");
            case REVOKED -> new Conflict("pilot_invite_revoked", "This invite was withdrawn.");
        };
        if (refusal != null) {
            throw refusal;
        }
    }
}
