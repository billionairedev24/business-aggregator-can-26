package ca.northline.console.web;

import ca.northline.console.application.StaffProfile;
import ca.northline.console.application.StaffProfile.RoleGrant;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The console shell's view of the signed-in staff member (S-90, docs/CONSOLE_PLAN.md § Roles): the roles they hold with
 * the screens and actions each grants — the console filters its sidebar and gates its routes with it, the api refuses
 * the rest ({@code @RequiresConsole}) — and the audit-logged role view switch.
 *
 * <pre>
 * GET  /api/v1/console/me                       {userId, roles: [{role, screens, actions}]}
 * POST /api/v1/console/me/role-view {role}      {role, screens, actions}   (403 role_not_held)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/me")
@RequiredArgsConstructor
class StaffProfileController {

    static final String ROLE_REQUIRED = "Choose a role.";

    private final StaffProfile profile;

    record MeResponse(String userId, List<RoleGrant> roles) {}

    record RoleViewRequest(
            @NotBlank(message = ROLE_REQUIRED) String role) {}

    @GetMapping
    @RequiresConsole(ConsoleScreen.PROFILE)
    MeResponse me(CurrentStaff staff) {
        return new MeResponse(staff.userId(), profile.rolesOf(staff));
    }

    @PostMapping("/role-view")
    @RequiresConsole(ConsoleScreen.PROFILE)
    RoleGrant switchView(@Valid @RequestBody RoleViewRequest body, CurrentStaff staff) {
        return profile.switchView(staff, body.role());
    }
}
