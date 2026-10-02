package ca.northline.auth.application;

import ca.northline.auth.application.FlowRejected.Reason;
import ca.northline.auth.application.RateLimiter.Decision;
import ca.northline.auth.application.RateLimiter.Limit;
import ca.northline.auth.domain.AuthMessages;
import ca.northline.auth.domain.PhoneNumber;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Rate limits per account, IP and auth session for codes, sign-in and factors (S-9), on top of the per-flow rules
 * (5 wrong tries per phone code, 45 s resend cool-down, 5 failed factors per sign-in attempt).
 *
 * <p>Answers {@code 429 rate_limited} with {@code Retry-After}. The account scope uses what was typed (normalised) and
 * behaves the same for unknown accounts, so the answer never reveals whether an account exists. Lockouts are written to
 * the audit log ({@code auth.rate_limited}).
 *
 * <p>S-20: when the store can't be reached, {@code northline.auth.rate-limits.when-unavailable} decides — {@code open}
 * lets the attempt through (logged), {@code closed} answers {@code 503 sign_in_unavailable} on the code/OTP and factor
 * paths ({@link LimitedAction#guardsSecret()}), so nobody can guess codes without a limit while Valkey is down.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttemptLimits {

    /** How long the Studio waits before trying again while the limit store is unreachable. */
    static final long UNAVAILABLE_RETRY_SECONDS = 30;

    private final RateLimiter limiter;
    private final RateLimitProperties props;
    private final RequestOrigin origin;
    private final SignInLog audit;

    /**
     * Who is trying.
     *
     * @param account normalised identifier or user id; null when unknown (e.g. a passkey without a typed identifier)
     * @param userId the matching account, for the audit log only; null for unknown identifiers
     */
    public record Subject(
            @Nullable String account, @Nullable String userId) {

        public static final Subject NOBODY = new Subject(null, null);

        /**
         * An email or mobile as typed. A known account counts by its id (so its email and mobile share one budget);
         * an unknown one by the normalised identifier (case-insensitive email, E.164 mobile) — same numbers, same
         * answers, so the limits don't tell them apart.
         */
        public static Subject identifier(String identifier, @Nullable String userId) {
            if (userId != null) {
                return user(userId);
            }
            var id = identifier.strip();
            var normalised = id.contains("@")
                    ? id.toLowerCase(Locale.ROOT)
                    : PhoneNumber.parse(id).map(PhoneNumber::e164).orElse(id.toLowerCase(Locale.ROOT));
            return new Subject(normalised, userId);
        }

        /** A signed-in person (step-up). */
        public static Subject user(String userId) {
            return new Subject("user:" + userId, userId);
        }
    }

    /** {@code REQUESTS} actions: counts this call; 429 when it goes over a limit. */
    public void consume(LimitedAction action, Subject who) {
        var limits = limits(action, who);
        if (!limits.isEmpty()) {
            enforce(action, who, decide(action, () -> limiter.record(limits)));
        }
    }

    /** {@code FAILURES} actions: 429 while locked, before the answer is even checked. */
    public void guard(LimitedAction action, Subject who) {
        var limits = limits(action, who);
        if (!limits.isEmpty()) {
            enforce(action, who, decide(action, () -> limiter.check(limits)));
        }
    }

    /** {@code FAILURES} actions: counts a wrong answer; returns the 429 when this failure locked, else {@code error}. */
    public RuntimeException failed(LimitedAction action, Subject who, RuntimeException error) {
        var limits = limits(action, who);
        if (limits.isEmpty()) {
            return error;
        }
        Decision decision;
        try {
            decision = decide(action, () -> limiter.record(limits));
        } catch (FlowRejected unavailable) {
            return unavailable; // fail closed: not even "wrong code" is revealed while nothing is counted
        }
        return decision.allowed() ? error : rejection(action, who, decision);
    }

    /**
     * A success resets the account and session counters (never the IP's: one good account must not unlock an IP; nor
     * the platform's).
     */
    public void succeeded(LimitedAction action, Subject who) {
        var limits = limits(action, who).stream()
                .filter(l -> l.scope() != LimitScope.IP && l.scope() != LimitScope.PLATFORM)
                .toList();
        if (!limits.isEmpty()) {
            limiter.reset(limits);
        }
    }

    /** The limiter's answer, or the S-20 policy when the store can't give one. */
    private Decision decide(LimitedAction action, Supplier<Decision> call) {
        try {
            return call.get();
        } catch (RateLimiter.Unavailable e) {
            if (action.guardsSecret() && props.whenUnavailable() == RateLimitProperties.WhenUnavailable.CLOSED) {
                log.error("Rate limits unavailable: {} refused (fail closed) — {}", action.code(), e.getMessage());
                throw new FlowRejected(Reason.UNAVAILABLE, AuthMessages.SIGN_IN_UNAVAILABLE, UNAVAILABLE_RETRY_SECONDS);
            }
            log.error("Rate limits unavailable: {} allowed (fail open) — {}", action.code(), e.getMessage());
            return Decision.ALLOWED;
        }
    }

    private void enforce(LimitedAction action, Subject who, Decision decision) {
        if (!decision.allowed()) {
            throw rejection(action, who, decision);
        }
    }

    private FlowRejected rejection(LimitedAction action, Subject who, Decision decision) {
        var seconds = Math.max(1, (decision.retryAfter().toMillis() + 999) / 1000);
        if (!decision.newlyLocked().isEmpty()) {
            var scopes = decision.newlyLocked().stream()
                    .map(LimitScope::code)
                    .sorted()
                    .toList();
            if (decision.newlyLocked().contains(LimitScope.PLATFORM)) {
                log.error(
                        "Rate limit: {} locked for everyone for {} s — platform budget reached",
                        action.code(),
                        seconds);
            } else {
                log.warn("Rate limit: {} locked for {} s ({})", action.code(), seconds, scopes);
            }
            audit.lockedOut(who.userId(), action.code(), scopes, seconds, new SignInLog.Client(origin.ip(), null));
        }
        return new FlowRejected(Reason.RATE_LIMITED, AuthMessages.RATE_LIMITED, seconds);
    }

    private List<Limit> limits(LimitedAction action, Subject who) {
        var rules = props.limits().getOrDefault(action, Map.of());
        var limits = new ArrayList<Limit>(4);
        for (var scope : LimitScope.values()) {
            var rule = rules.get(scope);
            var value = rule == null ? null : valueOf(scope, who);
            if (rule != null && value != null) {
                var threshold = action.kind() == LimitedAction.Kind.FAILURES ? rule.max() : rule.max() + 1;
                limits.add(new Limit(action, scope, hash(scope, value), rule, threshold, props.backoffMemory()));
            }
        }
        return limits;
    }

    private @Nullable String valueOf(LimitScope scope, Subject who) {
        return switch (scope) {
            case ACCOUNT -> who.account();
            case IP -> origin.ip();
            case SESSION -> origin.sessionId();
            case PLATFORM -> "all";
        };
    }

    /** Keys never hold an email, a mobile or an IP in clear. */
    private static String hash(LimitScope scope, String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest((scope.code() + ':' + value).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(digest, 18));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
