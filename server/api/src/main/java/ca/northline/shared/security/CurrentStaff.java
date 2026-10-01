package ca.northline.shared.security;

import java.util.Set;

/**
 * A staff member authorized for a console handler (S-90). Declare it as a controller parameter on a
 * {@link RequiresConsole} handler.
 *
 * @param userId ULID of {@code identity.users}
 * @param held every console role the token carries
 * @param active the roles this request acts with: {@code held}, or the one the {@code X-Console-Role} view names
 */
public record CurrentStaff(String userId, Set<StaffRole> held, Set<StaffRole> active) {
    public CurrentStaff {
        held = Set.copyOf(held);
        active = Set.copyOf(active);
    }

    /** The active roles' codes, comma-separated, for the audit log's {@code role} column. */
    public String roleCodes() {
        return active.stream()
                .map(StaffRole::code)
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse(StaffRole.STAFF);
    }
}
