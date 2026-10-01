package ca.northline.console.application;

import ca.northline.developer.api.AuditLogQuery;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Platform console — the platform screens of S-96 (design 03 {@code team}, {@code api}, {@code oncall}, {@code profile}):
 * the team and its console roles (granted here instead of by SQL), the audit log with filters, every business's API keys,
 * and the on-call rota. Every change is audit-logged with codes and ids only.
 */
public interface StaffAdmin {

    /** fr-CA in docs/spec/validation-messages.fr-CA.tsv. */
    String ROLE = "Choose a role from the list.";

    String EMAIL = "Enter the person's email.";
    String NO_ACCOUNT = "No Northline account uses that email. They sign up first, then you add the role.";
    String OWN_ADMIN = "You can't remove your own admin role.";
    String LAST_ADMIN = "Northline needs at least one admin.";
    String DUTY = "Say what the shift covers, 1 to 120 characters.";
    String SHIFT_TIMES = "A shift ends after it starts and lasts at most 7 days.";
    String STAFF_MEMBER = "Choose a staff member.";
    String NOT_YOURS = "Only the person on the shift or an admin can hand it over.";
    String DATE = "Use a date and time like 2026-09-08T18:00:00Z.";

    String PAGE = "That page link is no longer valid. Start from the first page.";
    String BUSINESS = "Choose a business.";

    /** An audit page cursor back from its token ({@code next}), or a 422 for a token the api didn't make. */
    static AuditLogQuery.@org.jspecify.annotations.Nullable Cursor cursor(@Nullable String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            var parts = new String(
                            java.util.Base64.getUrlDecoder().decode(token), java.nio.charset.StandardCharsets.UTF_8)
                    .split("\\|", 2);
            return new AuditLogQuery.Cursor(Instant.parse(parts[0]), parts[1]);
        } catch (RuntimeException e) {
            throw ca.northline.shared.RuleViolation.of("before", "pattern", PAGE);
        }
    }

    Team team();

    Member grant(String userId, String role, Actor actor);

    Member invite(String email, String role, Actor actor);

    Member revoke(String userId, String role, Actor actor);

    AuditPage audit(AuditLogQuery.Filter filter);

    List<KeyRow> keys();

    IssuedKey issueKey(String merchantId, String name, List<String> scopes, Actor actor);

    KeyRow revokeKey(String keyId, Actor actor);

    Rota rota(Instant from, Instant to);

    ShiftRow addShift(String userId, Instant startsAt, Instant endsAt, String duty, Actor actor);

    ShiftRow handOver(String shiftId, String userId, Actor actor, boolean admin);

    void removeShift(String shiftId, Actor actor);

    record Actor(String userId, String roles) {}

    /**
     * @param roles one row per console role: how many people hold it, its screens and actions (design "Roles":
     *     Role · People · Can · Needs)
     */
    record Team(List<RoleRow> roles, List<Member> members) {

        public Team {
            roles = List.copyOf(roles);
            members = List.copyOf(members);
        }
    }

    record RoleRow(String role, long people, List<String> screens, List<String> actions) {

        public RoleRow {
            screens = List.copyOf(screens);
            actions = List.copyOf(actions);
        }
    }

    record Member(
            String id,
            String name,
            @Nullable String email,
            List<String> roles,
            @Nullable Instant since) {

        public Member {
            roles = List.copyOf(roles);
        }
    }

    /** An audit entry with the actor's and the business's names filled in for display. */
    record AuditRow(
            String id,
            Instant at,
            @Nullable String actorId,
            @Nullable String actorName,
            @Nullable String role,
            String action,
            @Nullable String targetType,
            @Nullable String targetId,
            @Nullable String merchantId,
            @Nullable String businessName,
            @Nullable Map<String, Object> before,
            @Nullable Map<String, Object> after) {}

    record AuditPage(List<AuditRow> items, @Nullable String next) {

        public AuditPage {
            items = List.copyOf(items);
        }
    }

    record KeyRow(
            String id,
            String merchantId,
            @Nullable String businessName,
            String name,
            List<String> scopes,
            String prefix,
            int rateLimit,
            Instant createdAt,
            @Nullable Instant lastUsedAt,
            @Nullable Instant revokedAt) {

        public KeyRow {
            scopes = List.copyOf(scopes);
        }
    }

    record IssuedKey(KeyRow key, String secret) {}

    record ShiftRow(String id, String userId, String name, Instant startsAt, Instant endsAt, String duty) {}

    /** The shifts overlapping the period and who is on call now. */
    record Rota(Instant asOf, List<ShiftRow> shifts, List<ShiftRow> now, List<Member> staff) {

        public Rota {
            shifts = List.copyOf(shifts);
            now = List.copyOf(now);
            staff = List.copyOf(staff);
        }
    }
}
