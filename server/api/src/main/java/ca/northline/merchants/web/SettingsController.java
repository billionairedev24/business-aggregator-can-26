package ca.northline.merchants.web;

import static ca.northline.shared.security.MerchantPermission.MANAGE;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.merchants.application.SettingsActor;
import ca.northline.merchants.application.SettingsUseCases.ChangeTeamRole;
import ca.northline.merchants.application.SettingsUseCases.InvitationCreated;
import ca.northline.merchants.application.SettingsUseCases.InviteTeamMember;
import ca.northline.merchants.application.SettingsUseCases.MemberView;
import ca.northline.merchants.application.SettingsUseCases.RemoveTeamMember;
import ca.northline.merchants.application.SettingsUseCases.RevokeTeamInvitation;
import ca.northline.merchants.application.SettingsUseCases.TeamView;
import ca.northline.merchants.application.SettingsUseCases.UpdateBusinessSettings;
import ca.northline.merchants.application.SettingsUseCases.ViewBusinessSettings;
import ca.northline.merchants.application.SettingsUseCases.ViewTeam;
import ca.northline.merchants.web.SettingsDtos.BusinessResponse;
import ca.northline.merchants.web.SettingsDtos.ChangeRoleRequest;
import ca.northline.merchants.web.SettingsDtos.InviteRequest;
import ca.northline.merchants.web.SettingsDtos.UpdateBusinessRequest;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settings › Business and Settings › Team &amp; roles. Reads are open to the whole team; changes are owner-only.
 *
 * <pre>
 * GET    /api/v1/merchants/{merchantId}/settings/business                                   (VIEW)
 * PUT    /api/v1/merchants/{merchantId}/settings/business                                   (MANAGE)
 * GET    /api/v1/merchants/{merchantId}/settings/team         members + open invitations    (VIEW)
 * POST   /api/v1/merchants/{merchantId}/settings/team/invitations  {email|phone, role}      (MANAGE)
 * DELETE /api/v1/merchants/{merchantId}/settings/team/invitations/{invitationId}            (MANAGE)
 * PATCH  /api/v1/merchants/{merchantId}/settings/team/members/{userId}  {role}              (MANAGE)
 * DELETE /api/v1/merchants/{merchantId}/settings/team/members/{userId}                      (MANAGE)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/settings")
@RequiredArgsConstructor
class SettingsController {

    private final ViewBusinessSettings viewBusiness;
    private final UpdateBusinessSettings updateBusiness;
    private final ViewTeam viewTeam;
    private final InviteTeamMember invite;
    private final RevokeTeamInvitation revokeInvitation;
    private final ChangeTeamRole changeRole;
    private final RemoveTeamMember removeMember;

    @GetMapping("/business")
    @RequiresMerchant(VIEW)
    BusinessResponse business(@PathVariable String merchantId) {
        return BusinessResponse.of(viewBusiness.view(merchantId));
    }

    @PutMapping("/business")
    @RequiresMerchant(MANAGE)
    BusinessResponse updateBusiness(
            @PathVariable String merchantId, @Valid @RequestBody UpdateBusinessRequest body, CurrentMember member) {
        return BusinessResponse.of(updateBusiness.update(new UpdateBusinessSettings.Command(
                actor(member),
                body.displayName(),
                body.legalName(),
                body.gstNumber(),
                body.serviceArea(),
                body.cancellationPolicy(),
                body.autoAcceptQuoteCents(),
                body.languages())));
    }

    @GetMapping("/team")
    @RequiresMerchant(VIEW)
    TeamView team(@PathVariable String merchantId, CurrentMember member) {
        return viewTeam.view(merchantId, member.userId());
    }

    @PostMapping("/team/invitations")
    @RequiresMerchant(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    InvitationCreated invite(
            @PathVariable String merchantId, @Valid @RequestBody InviteRequest body, CurrentMember member) {
        return invite.invite(new InviteTeamMember.Command(actor(member), body.email(), body.phone(), body.role()));
    }

    @DeleteMapping("/team/invitations/{invitationId}")
    @RequiresMerchant(MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeInvitation(@PathVariable String merchantId, @PathVariable String invitationId, CurrentMember member) {
        revokeInvitation.revoke(actor(member), invitationId);
    }

    @PatchMapping("/team/members/{userId}")
    @RequiresMerchant(MANAGE)
    MemberView changeRole(
            @PathVariable String merchantId,
            @PathVariable String userId,
            @Valid @RequestBody ChangeRoleRequest body,
            CurrentMember member) {
        return changeRole.change(actor(member), userId, body.role());
    }

    @DeleteMapping("/team/members/{userId}")
    @RequiresMerchant(MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeMember(@PathVariable String merchantId, @PathVariable String userId, CurrentMember member) {
        removeMember.remove(actor(member), userId);
    }

    static SettingsActor actor(CurrentMember member) {
        return new SettingsActor(member.merchantId(), member.userId(), member.role());
    }
}
