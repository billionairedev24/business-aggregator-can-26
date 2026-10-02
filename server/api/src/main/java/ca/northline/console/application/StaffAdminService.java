package ca.northline.console.application;

import ca.northline.developer.api.AuditLogQuery;
import ca.northline.developer.api.AuditTrail;
import ca.northline.developer.api.PartnerKeys;
import ca.northline.identity.api.OncallRota;
import ca.northline.identity.api.PersonDirectory;
import ca.northline.identity.api.StaffDirectory;
import ca.northline.merchants.api.BusinessNames;
import ca.northline.shared.Conflict;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.StaffRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link StaffAdmin}: identity holds roles and the rota, developer the keys and the log; this checks and audits. */
@Service
@RequiredArgsConstructor
@Transactional
class StaffAdminService implements StaffAdmin {

    static final int AUDIT_PAGE = 50;

    private final StaffDirectory staff;
    private final OncallRota rota;
    private final PartnerKeys keys;
    private final AuditLogQuery log;
    private final AuditTrail audit;
    private final PersonDirectory people;
    private final BusinessNames businesses;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public Team team() {
        var members = staff.members().stream().map(StaffAdminService::member).toList();
        var rows = Arrays.stream(StaffRole.values())
                .map(r -> new RoleRow(
                        r.code(),
                        members.stream()
                                .filter(m -> m.roles().contains(r.code()))
                                .count(),
                        r.screens().stream().map(ConsoleScreen::code).sorted().toList(),
                        r.actions().stream().map(ConsoleAction::code).sorted().toList()))
                .toList();
        return new Team(rows, members);
    }

    @Override
    public Member grant(String userId, String role, Actor actor) {
        var code = role(role);
        var person = staff.member(userId).orElseThrow(() -> new NotFound("person", userId));
        if (staff.grant(person.id(), code, actor.userId())) {
            record(actor, "console.role_granted", "user", person.id(), null, Map.of("role", code));
        }
        return member(staff.member(person.id()).orElseThrow());
    }

    @Override
    public Member invite(String email, String role, Actor actor) {
        if (email.isBlank()) {
            throw RuleViolation.of("email", "required", EMAIL);
        }
        var code = role(role);
        var person = staff.byEmail(email).orElseThrow(() -> RuleViolation.of("email", "unknown", NO_ACCOUNT));
        return grant(person.id(), code, actor);
    }

    @Override
    public Member revoke(String userId, String role, Actor actor) {
        var code = role(role);
        var person = staff.member(userId).orElseThrow(() -> new NotFound("person", userId));
        if (StaffRole.ADMIN.code().equals(code) && person.roles().contains(code)) {
            if (person.id().equals(actor.userId())) {
                throw new Conflict("own_admin", OWN_ADMIN);
            }
            var admins = staff.members().stream()
                    .filter(m -> m.roles().contains(code))
                    .count();
            if (admins <= 1) {
                throw new Conflict("last_admin", LAST_ADMIN);
            }
        }
        if (staff.revoke(person.id(), code)) {
            record(actor, "console.role_revoked", "user", person.id(), Map.of("role", code), null);
        }
        return member(staff.member(person.id()).orElseThrow());
    }

    @Override
    @Transactional(readOnly = true)
    public AuditPage audit(AuditLogQuery.Filter filter) {
        var page = log.search(filter, AUDIT_PAGE);
        var actorIds = new HashSet<String>();
        var merchantIds = new HashSet<String>();
        page.items().forEach(e -> {
            if (e.actorId() != null) {
                actorIds.add(e.actorId());
            }
            if (e.merchantId() != null) {
                merchantIds.add(e.merchantId());
            }
        });
        var names = people.people(actorIds);
        var business = names(merchantIds);
        var rows = page.items().stream()
                .map(e -> new AuditRow(
                        e.id(),
                        e.at(),
                        e.actorId(),
                        e.actorId() == null ? null : name(names.get(e.actorId())),
                        e.role(),
                        e.action(),
                        e.targetType(),
                        e.targetId(),
                        e.merchantId(),
                        e.merchantId() == null ? null : business.get(e.merchantId()),
                        e.before(),
                        e.after()))
                .toList();
        return new AuditPage(rows, page.next() == null ? null : cursor(page.next()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<KeyRow> keys() {
        var all = keys.all();
        var business =
                names(all.stream().map(PartnerKeys.Key::merchantId).collect(java.util.stream.Collectors.toSet()));
        return all.stream().map(k -> keyRow(k, business.get(k.merchantId()))).toList();
    }

    @Override
    public IssuedKey issueKey(String merchantId, String name, List<String> scopes, Actor actor) {
        var business = businesses
                .displayName(merchantId)
                .orElseThrow(() -> RuleViolation.of("merchantId", "unknown", BUSINESS));
        var issued = keys.issue(merchantId, name, scopes, actor.userId(), actor.roles());
        return new IssuedKey(keyRow(issued.key(), business), issued.secret());
    }

    @Override
    public KeyRow revokeKey(String keyId, Actor actor) {
        var key = keys.revoke(keyId, actor.userId(), actor.roles());
        return keyRow(key, businesses.displayName(key.merchantId()).orElse(null));
    }

    @Override
    @Transactional(readOnly = true)
    public Rota rota(Instant from, Instant to) {
        var now = clock.instant();
        var shifts =
                rota.between(from, to).stream().map(StaffAdminService::shift).toList();
        var current = rota.between(now, now.plusSeconds(1)).stream()
                .map(StaffAdminService::shift)
                .toList();
        var team = staff.members().stream()
                .map(m -> new Member(m.id(), m.name(), null, m.roles(), m.grantedAt()))
                .toList();
        return new Rota(now, shifts, current, team);
    }

    @Override
    public ShiftRow addShift(String userId, Instant startsAt, Instant endsAt, String duty, Actor actor) {
        var problems = new java.util.ArrayList<RuleViolation.Violation>();
        if (staff.member(userId)
                .filter(m -> m.roles().contains(StaffRole.STAFF))
                .isEmpty()) {
            problems.add(new RuleViolation.Violation("userId", "unknown", STAFF_MEMBER));
        }
        if (duty.isBlank() || duty.strip().length() > 120) {
            problems.add(new RuleViolation.Violation("duty", "length", DUTY));
        }
        if (!endsAt.isAfter(startsAt) || Duration.between(startsAt, endsAt).compareTo(Duration.ofDays(7)) > 0) {
            problems.add(new RuleViolation.Violation("endsAt", "range", SHIFT_TIMES));
        }
        if (!problems.isEmpty()) {
            throw new RuleViolation(problems);
        }
        var shift = rota.add(userId, startsAt, endsAt, duty.strip(), actor.userId());
        record(actor, "console.oncall_shift_added", "oncall_shift", shift.id(), null, Map.of("userId", userId));
        return shift(shift);
    }

    @Override
    public ShiftRow handOver(String shiftId, String userId, Actor actor, boolean admin) {
        var shift = rota.shift(shiftId).orElseThrow(() -> new NotFound("shift", shiftId));
        if (!admin && !shift.userId().equals(actor.userId())) {
            throw new Conflict("not_your_shift", NOT_YOURS);
        }
        if (staff.member(userId)
                .filter(m -> m.roles().contains(StaffRole.STAFF))
                .isEmpty()) {
            throw RuleViolation.of("userId", "unknown", STAFF_MEMBER);
        }
        var after = rota.reassign(shiftId, userId);
        record(
                actor,
                "console.oncall_shift_swapped",
                "oncall_shift",
                shiftId,
                Map.of("userId", shift.userId()),
                Map.of("userId", userId));
        return shift(after);
    }

    @Override
    public void removeShift(String shiftId, Actor actor) {
        var shift = rota.shift(shiftId).orElseThrow(() -> new NotFound("shift", shiftId));
        rota.remove(shiftId);
        record(actor, "console.oncall_shift_removed", "oncall_shift", shiftId, Map.of("userId", shift.userId()), null);
    }

    /** A console role code ({@code admin}, {@code finance}…), never {@code staff} itself (it follows the others). */
    private static String role(String role) {
        return Arrays.stream(StaffRole.values())
                .map(StaffRole::code)
                .filter(c -> c.equals(role))
                .findFirst()
                .orElseThrow(() -> RuleViolation.of("role", "unknown", ROLE));
    }

    private Map<String, String> names(Set<String> merchantIds) {
        var out = new HashMap<String, String>();
        merchantIds.forEach(id -> businesses.displayName(id).ifPresent(n -> out.put(id, n)));
        return out;
    }

    private static @Nullable String name(PersonDirectory.@Nullable Person p) {
        return p == null ? null : p.displayName();
    }

    /** The cursor as one opaque url-safe token. */
    static String cursor(AuditLogQuery.Cursor c) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((c.at().toString() + "|" + c.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Member member(StaffDirectory.Member m) {
        return new Member(m.id(), m.name(), m.email(), m.roles(), m.grantedAt());
    }

    private static KeyRow keyRow(PartnerKeys.Key k, @Nullable String business) {
        return new KeyRow(
                k.id(),
                k.merchantId(),
                business,
                k.name(),
                k.scopes(),
                k.prefix(),
                k.rateLimit(),
                k.createdAt(),
                k.lastUsedAt(),
                k.revokedAt());
    }

    private static ShiftRow shift(OncallRota.Shift s) {
        return new ShiftRow(s.id(), s.userId(), s.name(), s.startsAt(), s.endsAt(), s.duty());
    }

    private void record(
            Actor actor,
            String action,
            String targetType,
            String targetId,
            @Nullable Map<String, ?> before,
            @Nullable Map<String, ?> after) {
        audit.record(
                new AuditTrail.Entry(null, actor.userId(), actor.roles(), action, targetType, targetId, before, after));
    }
}
