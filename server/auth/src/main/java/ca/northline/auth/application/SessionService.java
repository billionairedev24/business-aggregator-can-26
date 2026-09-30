package ca.northline.auth.application;

import ca.northline.auth.application.FlowRejected.Reason;
import ca.northline.auth.application.SignInSessions.EndReason;
import ca.northline.auth.domain.AuthMessages;
import ca.northline.auth.domain.IpAddresses;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Active sessions (S-19, Studio Settings › Security): list them, revoke one or all others, and keep them honest — an
 * ended session's auth-server HTTP session is dropped on its next request ({@link #stillOpen}) and its refresh tokens
 * are deleted at once, so the BFF's next refresh or revocation check fails and it drops its own session.
 *
 * <p>"Current" is the session of the request (the auth server's) plus, optionally, the BFF session's {@code sid} the
 * Studio passes along: after a "confirm it's you" sign-in the browser holds two sessions, and neither is revoked by
 * "sign out of all other sessions".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private static final String REVOKED = "auth.session_revoked";

    private final SignInSessions sessions;
    private final SecurityChanges changes;
    private final AuditTrail audit;
    private final SessionProperties props;
    private final Clock clock;

    /** A session as Settings › Security lists it. */
    public record ActiveSession(
            String id,
            @Nullable String device,
            @Nullable String city,
            @Nullable String ipApprox,
            @Nullable String method,
            Instant signedInAt,
            @Nullable Instant lastSeenAt,
            List<String> apps,
            boolean current) {}

    @Transactional(readOnly = true)
    public List<ActiveSession> list(Caller caller, @Nullable String alsoCurrent) {
        var current = currentIds(caller, alsoCurrent);
        var now = clock.instant();
        return sessions.open(caller.userId(), now.minus(props.idleTimeout()), now).stream()
                .map(s -> new ActiveSession(
                        s.id(),
                        s.device(),
                        s.city(),
                        IpAddresses.approximate(s.ip()),
                        s.method(),
                        s.signedInAt(),
                        s.lastSeenAt(),
                        s.apps(),
                        current.contains(s.id())))
                .toList();
    }

    /** Ends another session of the caller (its refresh tokens die at once, its BFF session within a minute). */
    @Transactional
    public List<ActiveSession> revoke(Caller caller, String sessionId, @Nullable String alsoCurrent) {
        if (currentIds(caller, alsoCurrent).contains(sessionId)) {
            throw new FlowRejected(Reason.CURRENT_SESSION, AuthMessages.CURRENT_SESSION);
        }
        changes.authorize(caller);
        var ended = sessions.end(caller.userId(), List.of(sessionId), EndReason.REVOKED, clock.instant());
        if (ended.isEmpty()) {
            throw new FlowRejected(Reason.GONE, AuthMessages.SESSION_GONE);
        }
        audit.record(caller.userId(), REVOKED, "session", sessionId, Map.of("reason", EndReason.REVOKED.code()));
        log.info("Session revoked: user={} session={}", caller.userId(), sessionId);
        return list(caller, alsoCurrent);
    }

    /** "Sign out of all other sessions": everything but the current one(s). Returns how many sessions ended. */
    @Transactional
    public int revokeOthers(Caller caller, @Nullable String alsoCurrent) {
        changes.authorize(caller);
        var keep = currentIds(caller, alsoCurrent);
        var ended = sessions.endAllExcept(caller.userId(), keep, EndReason.REVOKED_OTHERS, clock.instant());
        ended.forEach(id -> audit.record(
                caller.userId(), REVOKED, "session", id, Map.of("reason", EndReason.REVOKED_OTHERS.code())));
        log.info("Other sessions revoked: user={} ended={} kept={}", caller.userId(), ended.size(), keep);
        return ended.size();
    }

    /**
     * For every auth-server request with a signed-in session: false once the session has ended (the caller drops the
     * HTTP session). Also records "last seen", at most once per {@code touch-interval}.
     */
    @Transactional
    public boolean stillOpen(String sessionId) {
        var state = sessions.state(sessionId);
        if (state.isEmpty() || state.get().ended()) {
            return false;
        }
        var now = clock.instant();
        sessions.touch(sessionId, now, now.minus(props.touchInterval()));
        return true;
    }

    /** "Sign out" / "Not you?": the session ends everywhere, including the refresh tokens issued from it. */
    @Transactional
    public void signedOut(String userId, String sessionId) {
        sessions.end(userId, List.of(sessionId), EndReason.SIGNED_OUT, clock.instant());
    }

    /**
     * The authorization server stored an authorization issued from this session (code, tokens, a refresh or a
     * revocation): link it, note whether its refresh token still works, and count a working one as activity.
     */
    @Transactional
    public void authorizationSaved(String authorizationId, String sessionId, boolean refreshable) {
        sessions.link(authorizationId, sessionId, refreshable);
        if (refreshable) {
            var now = clock.instant();
            sessions.touch(sessionId, now, now.minus(props.touchInterval()));
        }
    }

    private Set<String> currentIds(Caller caller, @Nullable String alsoCurrent) {
        var ids = new LinkedHashSet<String>(2);
        if (caller.sessionId() != null) {
            ids.add(caller.sessionId());
        }
        if (alsoCurrent != null
                && sessions.state(alsoCurrent)
                        .filter(s -> s.userId().equals(caller.userId()) && !s.ended())
                        .isPresent()) {
            ids.add(alsoCurrent);
        }
        return ids;
    }
}
