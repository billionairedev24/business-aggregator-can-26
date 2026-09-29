package ca.northline.merchants.application;

import ca.northline.merchants.domain.BusinessSettings;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Inbound ports of Settings › Business and Settings › Team &amp; roles (one small interface each) and their views. */
public final class SettingsUseCases {
    private SettingsUseCases() {}

    public interface ViewBusinessSettings {
        BusinessSettings view(String merchantId);
    }

    public interface UpdateBusinessSettings {
        record Command(
                SettingsActor actor,
                String displayName,
                String legalName,
                @Nullable String gstNumber,
                @Nullable String serviceArea,
                String cancellationPolicy,
                @Nullable Long autoAcceptQuoteCents,
                List<String> languages) {}

        BusinessSettings update(Command command);
    }

    public interface ViewTeam {
        TeamView view(String merchantId, String viewerId);
    }

    public interface InviteTeamMember {
        record Command(
                SettingsActor actor,
                @Nullable String email,
                @Nullable String phone,
                @Nullable String role) {}

        InvitationCreated invite(Command command);
    }

    public interface RevokeTeamInvitation {
        void revoke(SettingsActor actor, String invitationId);
    }

    public interface ChangeTeamRole {
        MemberView change(SettingsActor actor, String userId, @Nullable String role);
    }

    public interface RemoveTeamMember {
        void remove(SettingsActor actor, String userId);
    }

    /** The invitee's view before accepting (signed in, not yet a member). */
    public interface PreviewTeamInvitation {
        InvitationPreview preview(String token, String userId);
    }

    public interface AcceptTeamInvitation {
        Accepted accept(String token, String userId, boolean mfa);
    }

    /**
     * @param roles the roles an owner can hand out in this business (owner first)
     */
    public record TeamView(List<MemberView> members, List<InvitationView> invitations, List<String> roles) {}

    /**
     * @param secondFactor {@code passkey} | {@code totp} | {@code sms}, or null (the 2FA column)
     */
    public record MemberView(
            String userId,
            String name,
            String role,
            @Nullable String secondFactor,
            boolean you,
            Instant joinedAt) {}

    /**
     * @param state {@code pending} | {@code expired}
     */
    public record InvitationView(
            String id,
            String role,
            @Nullable String email,
            @Nullable String phone,
            String state,
            Instant createdAt,
            Instant expiresAt) {}

    /** The invite link is returned once, so the owner can also share it themselves. */
    public record InvitationCreated(InvitationView invitation, String inviteUrl, boolean sent) {}

    /**
     * @param state {@code pending} | {@code expired} | {@code accepted} | {@code revoked}
     * @param forYou whether the signed-in account is the invited email / mobile
     */
    public record InvitationPreview(
            String merchantId, String businessName, String businessType, String role, String state, boolean forYou) {}

    public record Accepted(String merchantId, String role) {}
}
