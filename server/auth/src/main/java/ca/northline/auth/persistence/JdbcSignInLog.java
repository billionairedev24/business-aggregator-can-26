package ca.northline.auth.persistence;

import ca.northline.auth.application.SignInLog;
import ca.northline.auth.domain.Factor;
import com.github.f4b6a3.ulid.UlidCreator;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sign-in log. A success adds an {@code identity.sessions} row (the device list in Settings → Security) and an
 * {@code developer.audit_log} row ({@code auth.sign_in}); a failure only the audit row ({@code auth.sign_in_failed}).
 * Failures are written in their own transaction so they survive the caller's rollback.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
class JdbcSignInLog implements SignInLog {

    private static final int MAX_DEVICE = 200;

    private final JdbcClient jdbc;
    private final Clock clock;

    @Override
    public void succeeded(String userId, String method, boolean mfa, Client client) {
        var now = clock.instant().atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO identity.sessions (id, user_id, device, ip, created_at, last_seen_at, method, acr)
                        VALUES (:id, :user, :device, CAST(:ip AS inet), :at, :at, :method, :acr)
                        """)
                .param("id", UlidCreator.getMonotonicUlid().toString())
                .param("user", userId)
                .param("device", device(client.userAgent()))
                .param("ip", client.ip())
                .param("at", now)
                .param("method", method)
                .param("acr", mfa ? "mfa" : null)
                .update();
        audit(userId, "auth.sign_in", "{\"method\":\"%s\",\"mfa\":%s}".formatted(method, mfa));
        log.info("Sign-in: user={} method={} mfa={}", userId, method, mfa);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(@Nullable String userId, Factor factor, String reason, Client client) {
        audit(userId, "auth.sign_in_failed", "{\"method\":\"%s\",\"reason\":\"%s\"}".formatted(factor.code(), reason));
        log.info("Sign-in failed: user={} method={} reason={}", userId, factor.code(), reason);
    }

    private void audit(@Nullable String userId, String action, String after) {
        jdbc.sql("""
                        INSERT INTO developer.audit_log (id, actor_id, role, action, target_type, target_id, after, at)
                        VALUES (:id, :actor, 'user', :action, 'user', :actor, CAST(:after AS jsonb), :at)
                        """)
                .param("id", UlidCreator.getMonotonicUlid().toString())
                .param("actor", userId)
                .param("action", action)
                .param("after", after)
                .param("at", clock.instant().atOffset(ZoneOffset.UTC))
                .update();
    }

    private static @Nullable String device(@Nullable String userAgent) {
        var ua = Objects.requireNonNullElse(userAgent, "");
        return ua.isBlank() ? null : ua.substring(0, Math.min(ua.length(), MAX_DEVICE));
    }
}
