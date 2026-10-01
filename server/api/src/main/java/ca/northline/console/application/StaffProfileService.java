package ca.northline.console.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.StaffAccessDenied;
import ca.northline.shared.security.StaffRole;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class StaffProfileService implements StaffProfile {

    static final String ROLE_VIEW_SWITCHED = "console.role_view_switched";

    private final AuditTrail audit;

    @Override
    public List<RoleGrant> rolesOf(CurrentStaff staff) {
        return staff.held().stream()
                .sorted(Comparator.naturalOrder())
                .map(RoleGrant::of)
                .toList();
    }

    @Override
    @Transactional
    public RoleGrant switchView(CurrentStaff staff, String role) {
        var picked = StaffRole.fromCode(role)
                .filter(staff.held()::contains)
                .orElseThrow(() -> new StaffAccessDenied(
                        StaffAccessDenied.Reason.ROLE_NOT_HELD, StaffAccessDenied.ROLE_NOT_HELD_MESSAGE));
        // "You hold these roles; switching narrows what you see and can do. Logged." (design 03)
        audit.record(new AuditTrail.Entry(
                null,
                staff.userId(),
                staff.roleCodes(),
                ROLE_VIEW_SWITCHED,
                "staff_role",
                picked.code(),
                null,
                Map.of("role", picked.code())));
        return RoleGrant.of(picked);
    }
}
