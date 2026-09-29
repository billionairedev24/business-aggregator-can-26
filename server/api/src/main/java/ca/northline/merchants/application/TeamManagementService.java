package ca.northline.merchants.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.identity.api.TeamAccounts;
import ca.northline.merchants.api.TeamMembershipChanged;
import ca.northline.merchants.application.SettingsUseCases.AcceptTeamInvitation;
import ca.northline.merchants.application.SettingsUseCases.Accepted;
import ca.northline.merchants.application.SettingsUseCases.ChangeTeamRole;
import ca.northline.merchants.application.SettingsUseCases.InvitationCreated;
import ca.northline.merchants.application.SettingsUseCases.InvitationPreview;
import ca.northline.merchants.application.SettingsUseCases.InvitationView;
import ca.northline.merchants.application.SettingsUseCases.InviteTeamMember;
import ca.northline.merchants.application.SettingsUseCases.MemberView;
import ca.northline.merchants.application.SettingsUseCases.PreviewTeamInvitation;
import ca.northline.merchants.application.SettingsUseCases.RemoveTeamMember;
import ca.northline.merchants.application.SettingsUseCases.RevokeTeamInvitation;
import ca.northline.merchants.application.SettingsUseCases.TeamView;
import ca.northline.merchants.application.SettingsUseCases.ViewTeam;
import ca.northline.merchants.domain.Merchant;
import ca.northline.merchants.domain.TeamInvitation;
import ca.northline.merchants.domain.TeamRules;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.MerchantAccessDenied;
import ca.northline.shared.security.MerchantRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settings › Team &amp; roles (owner-only changes) and the invitee's accept flow. Membership rows are what
 * {@code MerchantAccess} checks on every request, so removal and role changes take effect at once. Every change is
 * audit-logged and published as {@code merchant.team_changed}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@EnableConfigurationProperties(StudioLinks.class)
class TeamManagementService
        implements ViewTeam,
                InviteTeamMember,
                RevokeTeamInvitation,
                ChangeTeamRole,
                RemoveTeamMember,
                PreviewTeamInvitation,
                AcceptTeamInvitation {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final TeamStore team;
    private final MerchantRepository merchants;
    private final TeamAccounts accounts;
    private final TeamInviteSender sender;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;
    private final StudioLinks links;
    private final Clock clock;

    @Override
    public TeamView view(String merchantId, String viewerId) {
        var merchant = merchant(merchantId);
        var seats = team.seats(merchantId);
        var people =
                accounts.accounts(seats.stream().map(TeamStore.Seat::userId).toList());
        var now = clock.instant();
        var members = seats.stream()
                .map(s -> {
                    var account = people.get(s.userId());
                    return new MemberView(
                            s.userId(),
                            account == null ? "" : account.displayName(),
                            s.role().code(),
                            account == null ? null : account.mfaPrimary(),
                            s.userId().equals(viewerId),
                            s.joinedAt());
                })
                .toList();
        var invitations =
                team.openInvitations(merchantId).stream().map(i -> view(i, now)).toList();
        var roles = TeamRules.rolesFor(merchant.getType()).stream()
                .map(MerchantRole::code)
                .toList();
        return new TeamView(members, invitations, roles);
    }

    @Override
    @Transactional
    public InvitationCreated invite(InviteTeamMember.Command command) {
        var actor = command.actor();
        var merchant = merchant(actor.merchantId());
        var contact = TeamRules.Contact.parse(command.email(), command.phone());
        var role = TeamRules.role(command.role(), merchant.getType());
        var now = clock.instant();
        var field = contact.email() != null ? "email" : "phone";
        var existing = team.seats(actor.merchantId()).stream()
                .map(TeamStore.Seat::userId)
                .toList();
        if (accounts.accounts(existing).values().stream().anyMatch(a -> contact.matches(a.email(), a.phone()))) {
            throw RuleViolation.of(field, "unique", TeamRules.ALREADY_MEMBER);
        }
        if (team.pendingInvitationFor(actor.merchantId(), contact, now)) {
            throw RuleViolation.of(field, "unique", TeamRules.ALREADY_INVITED);
        }
        team.revokeExpiredFor(actor.merchantId(), contact, now);
        var token = token();
        var invitation = new TeamInvitation(
                Ids.next(),
                actor.merchantId(),
                role,
                contact,
                actor.userId(),
                now,
                now.plus(TeamRules.INVITATION_TTL),
                null,
                null);
        team.insertInvitation(invitation, hash(token));
        audit.record(entry(actor, "team.invited", "invitation", invitation.id())
                .withChange(null, Map.of("role", role.code(), "channel", contact.channel())));
        var link = links.invitation(token);
        var sent = send(contact, merchant, actor, role, link);
        return new InvitationCreated(view(invitation, now), link, sent);
    }

    @Override
    @Transactional
    public void revoke(SettingsActor actor, String invitationId) {
        var invitation = team.invitation(actor.merchantId(), invitationId)
                .filter(i -> i.acceptedAt() == null && i.revokedAt() == null)
                .orElseThrow(() -> new NotFound("invitation", invitationId));
        team.markRevoked(invitation.id(), clock.instant());
        audit.record(entry(actor, "team.invitation_revoked", "invitation", invitation.id()));
    }

    @Override
    @Transactional
    public MemberView change(SettingsActor actor, String userId, @Nullable String roleCode) {
        var merchant = merchant(actor.merchantId());
        var seat = seat(actor.merchantId(), userId);
        var role = TeamRules.role(roleCode, merchant.getType());
        if (role != seat.role()) {
            if (seat.role() == MerchantRole.OWNER) {
                TeamRules.keepAnOwner(team.owners(actor.merchantId()) - 1);
            }
            team.changeRole(actor.merchantId(), userId, role);
            audit.record(entry(actor, "team.role_changed", "member", userId)
                    .withChange(Map.of("role", seat.role().code()), Map.of("role", role.code())));
            publish(actor.merchantId(), actor.userId(), userId, "role_changed", role);
        }
        var account = accounts.account(userId);
        return new MemberView(
                userId,
                account.map(TeamAccounts.Account::displayName).orElse(""),
                role.code(),
                account.map(TeamAccounts.Account::mfaPrimary).orElse(null),
                userId.equals(actor.userId()),
                seat.joinedAt());
    }

    @Override
    @Transactional
    public void remove(SettingsActor actor, String userId) {
        var seat = seat(actor.merchantId(), userId);
        if (seat.role() == MerchantRole.OWNER) {
            TeamRules.keepAnOwner(team.owners(actor.merchantId()) - 1);
        }
        team.removeSeat(actor.merchantId(), userId);
        audit.record(entry(actor, "team.removed", "member", userId)
                .withChange(Map.of("role", seat.role().code()), null));
        publish(actor.merchantId(), actor.userId(), userId, "removed", null);
    }

    @Override
    public InvitationPreview preview(String token, String userId) {
        var invitation = byToken(token);
        var merchant = merchant(invitation.merchantId());
        var account = accounts.account(userId);
        var forYou = account.map(a -> invitation.contact().matches(a.email(), a.phone()))
                .orElse(false);
        return new InvitationPreview(
                merchant.getId(),
                merchant.getDisplayName().value(),
                merchant.getType().code(),
                invitation.role().code(),
                invitation.state(clock.instant()).code(),
                forYou);
    }

    @Override
    @Transactional
    public Accepted accept(String token, String userId, boolean mfa) {
        var invitation = byToken(token);
        var now = clock.instant();
        invitation.requireUsable(now);
        if (!mfa) {
            throw new MerchantAccessDenied(
                    MerchantAccessDenied.Reason.MFA_REQUIRED, "Sign in with a passkey or authenticator first.");
        }
        var account = accounts.account(userId).orElseThrow(() -> new NotFound("user", userId));
        if (!invitation.contact().matches(account.email(), account.phone())) {
            throw new Conflict(
                    "invitation_not_for_you",
                    "This invitation was sent to a different email or mobile. Sign in with that account.");
        }
        if (team.seat(invitation.merchantId(), userId).isPresent()) {
            throw new Conflict("already_member", TeamRules.ALREADY_MEMBER);
        }
        team.addSeat(invitation.merchantId(), userId, invitation.role(), invitation.invitedBy(), now);
        team.markAccepted(invitation.id(), userId, now);
        audit.record(AuditTrail.Entry.of(
                invitation.merchantId(), userId, invitation.role().code(), "team.joined", "member", userId));
        publish(invitation.merchantId(), userId, userId, "joined", invitation.role());
        return new Accepted(invitation.merchantId(), invitation.role().code());
    }

    private boolean send(
            TeamRules.Contact contact, Merchant merchant, SettingsActor actor, MerchantRole role, String link) {
        try {
            var inviter = accounts.account(actor.userId())
                    .map(TeamAccounts.Account::displayName)
                    .orElse("");
            sender.send(new TeamInviteSender.Invite(
                    contact, merchant.getDisplayName().value(), inviter, role, link));
            return true;
        } catch (RuntimeException e) {
            log.warn("Team invitation for {} was not delivered: {}", merchant.getId(), e.getMessage());
            return false;
        }
    }

    private TeamInvitation byToken(String token) {
        return team.invitationByTokenHash(hash(token)).orElseThrow(() -> new NotFound("invitation", "token"));
    }

    private Merchant merchant(String merchantId) {
        return merchants.findById(merchantId).orElseThrow(() -> new NotFound("merchant", merchantId));
    }

    private TeamStore.Seat seat(String merchantId, String userId) {
        return team.seat(merchantId, userId).orElseThrow(() -> new NotFound("team member", userId));
    }

    private void publish(String merchantId, String actorId, String userId, String change, @Nullable MerchantRole role) {
        events.publishEvent(new TeamMembershipChanged(
                Ids.next(), clock.instant(), merchantId, actorId, userId, change, role == null ? null : role.code()));
    }

    private static InvitationView view(TeamInvitation i, Instant now) {
        return new InvitationView(
                i.id(),
                i.role().code(),
                i.contact().email(),
                i.contact().phone(),
                i.state(now).code(),
                i.createdAt(),
                i.expiresAt());
    }

    private static AuditTrail.Entry entry(SettingsActor actor, String action, String targetType, String targetId) {
        return AuditTrail.Entry.of(
                actor.merchantId(), actor.userId(), actor.role().code(), action, targetType, targetId);
    }

    private static String token() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
