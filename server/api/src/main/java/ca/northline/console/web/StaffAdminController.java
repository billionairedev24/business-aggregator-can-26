package ca.northline.console.web;

import ca.northline.console.application.StaffAdmin;
import ca.northline.console.application.StaffAdmin.Actor;
import ca.northline.console.application.StaffAdmin.AuditPage;
import ca.northline.console.application.StaffAdmin.IssuedKey;
import ca.northline.console.application.StaffAdmin.KeyRow;
import ca.northline.console.application.StaffAdmin.Member;
import ca.northline.console.application.StaffAdmin.Rota;
import ca.northline.console.application.StaffAdmin.ShiftRow;
import ca.northline.console.application.StaffAdmin.Team;
import ca.northline.developer.api.AuditLogQuery;
import ca.northline.shared.ListResponse;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import ca.northline.shared.security.StaffRole;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform console — team, audit log, API keys, on-call and my audit trail (S-96, design 03 {@code team}, {@code api},
 * {@code oncall}, {@code profile}).
 *
 * <pre>
 * GET    /api/v1/console/team                                  (team) Team
 * POST   /api/v1/console/team/invite {email, role}            (team · province = admin) Member  422 no account
 * POST   /api/v1/console/team/{userId}/roles {role}            (team · province) Member
 * DELETE /api/v1/console/team/{userId}/roles/{role}            (team · province) Member  409 own_admin · last_admin
 * GET    /api/v1/console/audit?actor=&amp;action=&amp;business=&amp;target=&amp;from=&amp;to=&amp;before=   (team) AuditPage
 * GET    /api/v1/console/me/audit?before=                       (profile) my own entries
 * GET    /api/v1/console/api-keys                               (api) {items: [KeyRow]}
 * POST   /api/v1/console/api-keys {merchantId, name, scopes}   (api · keys) 201 IssuedKey (secret shown once)
 * POST   /api/v1/console/api-keys/{keyId}/revoke               (api · keys) KeyRow
 * GET    /api/v1/console/oncall?from=&amp;to=                       (oncall, every staff member) Rota
 * POST   /api/v1/console/oncall/shifts {userId, startsAt, endsAt, duty}   (oncall · province = admin) 201 ShiftRow
 * POST   /api/v1/console/oncall/shifts/{id}/hand-over {userId} (oncall; the person on it, or an admin)
 * DELETE /api/v1/console/oncall/shifts/{id}                    (oncall · province)
 * </pre>
 */
@RestController
@RequiredArgsConstructor
class StaffAdminController {

    private final StaffAdmin admin;

    record RoleRequest(@Nullable String role) {}

    record InviteRequest(@Nullable String email, @Nullable String role) {}

    record KeyRequest(
            @Nullable String merchantId,
            @Nullable String name,
            @Nullable List<String> scopes) {}

    record ShiftRequest(
            @Nullable String userId,
            @Nullable String startsAt,
            @Nullable String endsAt,
            @Nullable String duty) {}

    record HandOverRequest(@Nullable String userId) {}

    @GetMapping("/api/v1/console/team")
    @RequiresConsole(ConsoleScreen.TEAM)
    Team team() {
        return admin.team();
    }

    @PostMapping("/api/v1/console/team/invite")
    @RequiresConsole(value = ConsoleScreen.TEAM, actions = ConsoleAction.PROVINCE)
    Member invite(@RequestBody InviteRequest body, CurrentStaff staff) {
        return admin.invite(text(body.email()), text(body.role()), actor(staff));
    }

    @PostMapping("/api/v1/console/team/{userId}/roles")
    @RequiresConsole(value = ConsoleScreen.TEAM, actions = ConsoleAction.PROVINCE)
    Member grant(@PathVariable String userId, @RequestBody RoleRequest body, CurrentStaff staff) {
        return admin.grant(userId, text(body.role()), actor(staff));
    }

    @DeleteMapping("/api/v1/console/team/{userId}/roles/{role}")
    @RequiresConsole(value = ConsoleScreen.TEAM, actions = ConsoleAction.PROVINCE)
    Member revoke(@PathVariable String userId, @PathVariable String role, CurrentStaff staff) {
        return admin.revoke(userId, role, actor(staff));
    }

    @GetMapping("/api/v1/console/audit")
    @RequiresConsole(ConsoleScreen.TEAM)
    AuditPage audit(
            @RequestParam(required = false) @Nullable String actor,
            @RequestParam(required = false) @Nullable String action,
            @RequestParam(required = false) @Nullable String business,
            @RequestParam(required = false) @Nullable String target,
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to,
            @RequestParam(required = false) @Nullable String before) {
        return admin.audit(new AuditLogQuery.Filter(
                blank(actor),
                blank(action),
                blank(business),
                blank(target),
                instant(from, "from"),
                instant(to, "to"),
                StaffAdmin.cursor(before)));
    }

    @GetMapping("/api/v1/console/me/audit")
    @RequiresConsole(ConsoleScreen.PROFILE)
    AuditPage myAudit(@RequestParam(required = false) @Nullable String before, CurrentStaff staff) {
        return admin.audit(
                new AuditLogQuery.Filter(staff.userId(), null, null, null, null, null, StaffAdmin.cursor(before)));
    }

    @GetMapping("/api/v1/console/api-keys")
    @RequiresConsole(ConsoleScreen.API)
    ListResponse<KeyRow> keys() {
        return new ListResponse<>(admin.keys());
    }

    @PostMapping("/api/v1/console/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresConsole(value = ConsoleScreen.API, actions = ConsoleAction.KEYS)
    IssuedKey issue(@RequestBody KeyRequest body, CurrentStaff staff) {
        if (body.merchantId() == null || body.merchantId().isBlank()) {
            throw RuleViolation.of("merchantId", "required", StaffAdmin.BUSINESS);
        }
        return admin.issueKey(
                body.merchantId(), text(body.name()), body.scopes() == null ? List.of() : body.scopes(), actor(staff));
    }

    @PostMapping("/api/v1/console/api-keys/{keyId}/revoke")
    @RequiresConsole(value = ConsoleScreen.API, actions = ConsoleAction.KEYS)
    KeyRow revokeKey(@PathVariable String keyId, CurrentStaff staff) {
        return admin.revokeKey(keyId, actor(staff));
    }

    @GetMapping("/api/v1/console/oncall")
    @RequiresConsole(ConsoleScreen.ONCALL)
    Rota rota(
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to) {
        var start = instant(from, "from");
        var begin = start == null ? Instant.now().minus(Duration.ofHours(12)) : start;
        var end = instant(to, "to");
        return admin.rota(begin, end == null ? begin.plus(Duration.ofDays(8)) : end);
    }

    @PostMapping("/api/v1/console/oncall/shifts")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresConsole(value = ConsoleScreen.ONCALL, actions = ConsoleAction.PROVINCE)
    ShiftRow addShift(@RequestBody ShiftRequest body, CurrentStaff staff) {
        var startsAt = instant(body.startsAt(), "startsAt");
        var endsAt = instant(body.endsAt(), "endsAt");
        if (startsAt == null || endsAt == null) {
            throw RuleViolation.of(startsAt == null ? "startsAt" : "endsAt", "required", StaffAdmin.DATE);
        }
        return admin.addShift(text(body.userId()), startsAt, endsAt, text(body.duty()), actor(staff));
    }

    @PostMapping("/api/v1/console/oncall/shifts/{id}/hand-over")
    @RequiresConsole(ConsoleScreen.ONCALL)
    ShiftRow handOver(@PathVariable String id, @RequestBody HandOverRequest body, CurrentStaff staff) {
        return admin.handOver(
                id, text(body.userId()), actor(staff), staff.active().contains(StaffRole.ADMIN));
    }

    @DeleteMapping("/api/v1/console/oncall/shifts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresConsole(value = ConsoleScreen.ONCALL, actions = ConsoleAction.PROVINCE)
    void removeShift(@PathVariable String id, CurrentStaff staff) {
        admin.removeShift(id, actor(staff));
    }

    private static @Nullable Instant instant(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw RuleViolation.of(field, "pattern", StaffAdmin.DATE);
        }
    }

    private static String text(@Nullable String value) {
        return value == null ? "" : value.strip();
    }

    private static @Nullable String blank(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static Actor actor(CurrentStaff staff) {
        return new Actor(staff.userId(), staff.roleCodes());
    }
}
