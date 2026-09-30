package ca.northline.auth.application;

import ca.northline.auth.application.SignInSessions.EndReason;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh-token reuse detection for public clients (S-29; S-20 had accepted its absence while only confidential BFFs
 * existed). Refresh tokens rotate on every use; each one issued to a public client is remembered by hash for the life
 * of its family (the authorization). A rotated one presented again means two parties hold the family — the app and
 * someone who copied a token — so the whole family is revoked and the sign-in it came from ends, like a revocation in
 * Settings › Security ({@code revoke_reason = refresh_token_reused}). Whoever holds the current token has to sign in
 * again.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenReuse {

    static final String REUSED = "auth.refresh_token_reused";
    private static final String SESSION_REVOKED = "auth.session_revoked";

    private final IssuedRefreshTokens issued;
    private final SignInSessions sessions;
    private final AuditTrail audit;
    private final Clock clock;

    /** A refresh token was issued to a public client in this family. */
    @Transactional
    public void issued(String token, String familyId, Instant issuedAt, Instant expiresAt) {
        issued.remember(hash(token), familyId, issuedAt, expiresAt);
    }

    /** The family a token no longer current belonged to — i.e. the token was rotated and is being reused. */
    @Transactional(readOnly = true)
    public Optional<String> familyOf(String token) {
        return issued.familyOf(hash(token));
    }

    /**
     * Revokes the family and ends its sign-in ({@code sessionId}; {@code null} for a family from before S-19 sessions,
     * then only the family goes).
     */
    @Transactional
    public void revoke(String userId, @Nullable String sessionId, String familyId, String clientId) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("client", clientId);
        detail.put("family", familyId);
        List<String> ended = sessionId == null
                ? List.of()
                : sessions.end(userId, List.of(sessionId), EndReason.REFRESH_TOKEN_REUSED, clock.instant());
        sessions.deleteAuthorization(familyId);
        audit.record(
                userId,
                REUSED,
                sessionId == null ? "authorization" : "session",
                sessionId == null ? familyId : sessionId,
                detail);
        ended.forEach(id -> audit.record(
                userId, SESSION_REVOKED, "session", id, Map.of("reason", EndReason.REFRESH_TOKEN_REUSED.code())));
        log.warn(
                "Refresh token reused: client={} user={} session={} — refresh-token family revoked, sign-in ended",
                clientId,
                userId,
                sessionId);
    }

    /** Base64url SHA-256: the table never holds a usable token. */
    static String hash(String token) {
        try {
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
