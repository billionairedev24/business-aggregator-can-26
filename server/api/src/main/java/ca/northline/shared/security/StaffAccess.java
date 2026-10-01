package ca.northline.shared.security;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Staff authorization for the platform console (S-90). {@link RequiresConsole} handlers are checked by the web
 * interceptor; call {@link #require} directly where an annotation doesn't fit.
 */
@Component
public class StaffAccess {

    /** Narrows a request to one held role (the console's "Switch role view"). */
    public static final String ROLE_VIEW_HEADER = "X-Console-Role";

    /**
     * Authorizes {@code user} for {@code screen} and every one of {@code actions}, acting with {@code roleView} only
     * when given, or throws {@link StaffAccessDenied}.
     */
    public CurrentStaff require(
            CurrentUser user, @Nullable String roleView, ConsoleScreen screen, Collection<ConsoleAction> actions) {
        if (user.roles().stream().noneMatch(StaffRole.STAFF::equalsIgnoreCase)) { // authorities are upper-case
            throw new StaffAccessDenied(StaffAccessDenied.Reason.NOT_STAFF, StaffAccessDenied.NOT_STAFF_MESSAGE);
        }
        if (!user.mfa()) {
            throw new StaffAccessDenied(StaffAccessDenied.Reason.MFA_REQUIRED, StaffAccessDenied.MFA_REQUIRED_MESSAGE);
        }
        var held = StaffRole.held(user.roles());
        var active = active(held, roleView);
        if (!screen.openToAllStaff() && active.stream().noneMatch(r -> r.opens(screen))) {
            throw new StaffAccessDenied(StaffAccessDenied.Reason.INSUFFICIENT_ROLE, StaffAccessDenied.SCREEN_MESSAGE);
        }
        for (var action : actions) {
            if (active.stream().noneMatch(r -> r.opens(screen) && r.allows(action))) {
                throw new StaffAccessDenied(
                        StaffAccessDenied.Reason.INSUFFICIENT_ROLE, StaffAccessDenied.ACTION_MESSAGE);
            }
        }
        return new CurrentStaff(user.userId(), held, active);
    }

    private static Set<StaffRole> active(Set<StaffRole> held, @Nullable String roleView) {
        if (roleView == null || roleView.isBlank()) {
            return held;
        }
        var role = StaffRole.fromCode(roleView.strip())
                .filter(held::contains)
                .orElseThrow(() -> new StaffAccessDenied(
                        StaffAccessDenied.Reason.ROLE_NOT_HELD, StaffAccessDenied.ROLE_NOT_HELD_MESSAGE));
        return Set.copyOf(EnumSet.of(role));
    }
}
