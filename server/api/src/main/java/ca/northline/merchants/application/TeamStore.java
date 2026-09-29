package ca.northline.merchants.application;

import ca.northline.merchants.domain.TeamInvitation;
import ca.northline.merchants.domain.TeamRules;
import ca.northline.shared.security.MerchantRole;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Outbound port: {@code merchants.merchant_members} and {@code merchants.member_invitations}. */
public interface TeamStore {

    /** Owner first, then by join date. */
    List<Seat> seats(String merchantId);

    Optional<Seat> seat(String merchantId, String userId);

    long owners(String merchantId);

    void addSeat(String merchantId, String userId, MerchantRole role, String invitedBy, Instant at);

    void changeRole(String merchantId, String userId, MerchantRole role);

    void removeSeat(String merchantId, String userId);

    /** Invitations neither accepted nor revoked (expired ones included), newest first. */
    List<TeamInvitation> openInvitations(String merchantId);

    Optional<TeamInvitation> invitation(String merchantId, String invitationId);

    Optional<TeamInvitation> invitationByTokenHash(String tokenHash);

    boolean pendingInvitationFor(String merchantId, TeamRules.Contact contact, Instant now);

    /** Retires an expired invitation for the same contact so a fresh one can be sent. */
    void revokeExpiredFor(String merchantId, TeamRules.Contact contact, Instant now);

    void insertInvitation(TeamInvitation invitation, String tokenHash);

    void markAccepted(String invitationId, String userId, Instant at);

    void markRevoked(String invitationId, Instant at);

    /** A member's seat on the team. */
    record Seat(String userId, MerchantRole role, Instant joinedAt) {}
}
