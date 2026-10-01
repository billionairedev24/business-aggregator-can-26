package ca.northline.console.application;

import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.StaffRole;
import java.util.Comparator;
import java.util.List;

/**
 * Use cases of the console shell (S-90): what a staff member may open and do, per held role — the console filters its
 * sidebar and gates its routes with it — and switching the role view, which is audit-logged.
 */
public interface StaffProfile {

    /** One held role: the screens it opens (sorted as the design's sidebar) and what it allows. */
    record RoleGrant(StaffRole role, List<ConsoleScreen> screens, List<ConsoleAction> actions) {
        public RoleGrant {
            screens = List.copyOf(screens);
            actions = List.copyOf(actions);
        }

        static RoleGrant of(StaffRole role) {
            return new RoleGrant(
                    role,
                    role.screens().stream().sorted(Comparator.naturalOrder()).toList(),
                    role.actions().stream().sorted(Comparator.naturalOrder()).toList());
        }
    }

    /** The caller's console roles (design order: admin, trust &amp; safety, dispatch, finance, support, analyst). */
    List<RoleGrant> rolesOf(CurrentStaff staff);

    /** Records that the caller now views the console as {@code role} (one they hold); returns its grant. */
    RoleGrant switchView(CurrentStaff staff, String role);
}
