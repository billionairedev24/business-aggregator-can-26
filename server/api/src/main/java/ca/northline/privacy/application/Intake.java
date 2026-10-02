package ca.northline.privacy.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.identity.api.AccountFacts;
import ca.northline.identity.api.PrivacyAccounts;
import ca.northline.identity.api.PrivacyAccounts.Holder;
import ca.northline.privacy.application.PrivacyRequestStore.Request;
import ca.northline.privacy.domain.PrivacyRules;
import ca.northline.privacy.domain.PrivacyRules.Correction;
import ca.northline.privacy.domain.RequestState;
import ca.northline.privacy.domain.RequestType;
import ca.northline.privacy.domain.SubjectKind;
import ca.northline.privacy.domain.Verification;
import ca.northline.region.api.MerchantPlaces;
import ca.northline.region.api.PrivacyRegimes;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.MerchantMemberships;
import ca.northline.shared.security.MerchantMemberships.Membership;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Opening requests and the transitions people and staff share: the SLA clock from the person's province's law, the
 * grace period of an erasure, the account's "Deletion requested" mark, the verification code, and the audit log.
 */
@Component
@RequiredArgsConstructor
class Intake {

    static final String SYSTEM = "system";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PrivacyRequestStore store;
    private final PrivacyAccounts accounts;
    private final AccountFacts facts;
    private final MerchantMemberships memberships;
    private final MerchantPlaces places;
    private final PrivacyRegimes regimes;
    private final Contributors contributors;
    private final Secrets secrets;
    private final AuditTrail audit;
    private final PrivacySettings settings;

    Holder holder(String userId) {
        var holder = accounts.holder(userId).orElseThrow(() -> new NotFound("account", userId));
        if (holder.erased()) {
            throw new Conflict("account_erased", PrivacyRules.ACCOUNT_ERASED);
        }
        return holder;
    }

    /**
     * A new request, verified ({@code verification} given) or awaiting verification.
     *
     * @param createdBy staff who recorded it, null when the person asked themselves
     */
    Request open(
            Holder holder,
            RequestType type,
            @Nullable List<Correction> asked,
            @Nullable String note,
            @Nullable Verification verification,
            @Nullable String createdBy,
            Instant now) {
        if (store.hasOpen(holder.id(), type)) {
            throw new Conflict("request_open", PrivacyRules.REQUEST_OPEN);
        }
        var corrections = type == RequestType.CORRECTION
                ? PrivacyRules.corrections(asked, contributors.correctable())
                : List.<Correction>of();
        var trimmedNote = note == null || note.isBlank() ? null : note.strip();
        if (trimmedNote != null && trimmedNote.length() > PrivacyRules.NOTE_MAX) {
            throw RuleViolation.of("note", "length", PrivacyRules.NOTE_LENGTH);
        }
        var merchantIds = memberships.membershipsOf(holder.id()).stream()
                .map(Membership::merchantId)
                .distinct()
                .sorted()
                .toList();
        var province = facts.of(holder.id()).defaultProvince();
        if (province == null && !merchantIds.isEmpty()) {
            province = places.of(merchantIds.getFirst()).province();
        }
        var regime = regimes.forProvince(province);
        var id = Ids.next();
        var request = new Request(
                id,
                0,
                holder.id(),
                merchantIds.isEmpty() ? SubjectKind.CUSTOMER : SubjectKind.TEAM,
                merchantIds,
                type,
                verification == null ? RequestState.AWAITING_VERIFICATION : RequestState.VERIFIED,
                createdBy == null ? "self" : "staff",
                regime.province().isEmpty() ? "--" : regime.province(),
                regime.law(),
                now,
                regimes.deadline(regime, now, regime.responseDays()),
                null,
                null,
                verification,
                verification == null ? null : now,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                createdBy,
                secrets.seal(id, new Secrets.Content(holder.email(), holder.phone(), corrections, trimmedNote)),
                null,
                null,
                null,
                null,
                null,
                0,
                0);
        if (verification != null) {
            request = request.withScheduledFor(scheduleFor(request, now));
        }
        var saved = store.insert(request);
        record(
                saved,
                createdBy == null ? holder.id() : createdBy,
                createdBy == null ? "self" : "staff",
                "opened",
                Map.of("type", type.code(), "law", regime.law().code(), "channel", saved.channel()));
        if (verification != null) {
            verified(saved, now);
        }
        return saved;
    }

    /** When verified work starts: an export at once, an erasure after its grace (a day before the deadline at most). */
    @Nullable
    Instant scheduleFor(Request r, Instant now) {
        return switch (r.type()) {
            case ACCESS -> now;
            case CORRECTION -> null;
            case ERASURE -> {
                var latest = r.deadline().minus(Duration.ofDays(1));
                var start = now.plus(settings.grace());
                yield start.isAfter(latest) ? (latest.isBefore(now) ? now : latest) : start;
            }
        };
    }

    /** What follows verification: the account shows "Deletion requested on …". */
    void verified(Request r, Instant at) {
        if (r.type() == RequestType.ERASURE) {
            accounts.erasureRequested(r.subjectId(), at);
        }
    }

    /** What follows a request closing without completing (withdrawn, rejected). */
    Request closedUnfinished(Request r) {
        if (r.type() == RequestType.ERASURE) {
            accounts.erasureRequested(r.subjectId(), null);
        }
        return r.withSealed(null).withCodeHash(null).withCodeExpiresAt(null);
    }

    Request save(Request r) {
        if (!store.save(r)) {
            throw new Conflict("stale", "This request changed meanwhile. Reload it and try again.");
        }
        return store.find(r.id()).orElseThrow();
    }

    /** A new 6-digit code: returns the request with its hash and expiry, and the code to text. */
    CodeIssued newCode(Request r, Instant now) {
        var code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        return new CodeIssued(
                r.withCodeHash(sha256(r.id() + ":" + code))
                        .withCodeExpiresAt(now.plus(settings.codeLife()))
                        .withCodeAttempts(0),
                code);
    }

    record CodeIssued(Request request, String code) {}

    boolean codeMatches(Request r, String code, Instant now) {
        return r.codeHash() != null
                && r.codeExpiresAt() != null
                && now.isBefore(r.codeExpiresAt())
                && MessageDigest.isEqual(
                        r.codeHash().getBytes(StandardCharsets.UTF_8),
                        sha256(r.id() + ":" + code).getBytes(StandardCharsets.UTF_8));
    }

    /** The audit log entry of a privacy request action ({@code privacy.request_<what>}); ids and codes only. */
    void record(Request r, String actorId, String role, String what, Map<String, ?> after) {
        audit.record(new AuditTrail.Entry(
                null, actorId, role, "privacy.request_" + what, "privacy_request", r.id(), null, after));
    }

    static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String token() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static Map<String, Object> codes(Object... pairs) {
        var map = new java.util.LinkedHashMap<String, Object>();
        for (var i = 0; i < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), Objects.requireNonNull(pairs[i + 1]));
        }
        return map;
    }
}
