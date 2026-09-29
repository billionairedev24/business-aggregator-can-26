package ca.northline.merchants.web;

import ca.northline.merchants.application.SettingsUseCases.AcceptTeamInvitation;
import ca.northline.merchants.application.SettingsUseCases.Accepted;
import ca.northline.merchants.application.SettingsUseCases.InvitationPreview;
import ca.northline.merchants.application.SettingsUseCases.PreviewTeamInvitation;
import ca.northline.shared.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The invitee's side of a team invitation (Studio {@code /invite/<token>}), for any signed-in user:
 *
 * <pre>
 * GET  /api/v1/team-invitations/{token}          business, role, state, whether it was sent to you
 * POST /api/v1/team-invitations/{token}/accept   → {merchantId, role}; needs acr=mfa and the invited email / mobile
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/team-invitations/{token}")
@RequiredArgsConstructor
class TeamInvitationController {

    private final PreviewTeamInvitation preview;
    private final AcceptTeamInvitation accept;

    @GetMapping
    InvitationPreview preview(@PathVariable String token, CurrentUser user) {
        return preview.preview(token, user.userId());
    }

    @PostMapping("/accept")
    Accepted accept(@PathVariable String token, CurrentUser user) {
        return accept.accept(token, user.userId(), user.mfa());
    }
}
