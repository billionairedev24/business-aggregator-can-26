package ca.northline.auth.persistence;

import ca.northline.auth.application.SignInSessions;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link SignInSessions} over {@code identity.sessions}, {@code auth.authorization_sessions} and Spring Authorization
 * Server's {@code auth.oauth2_authorization}. Ending a session deletes its authorizations in the same transaction
 * (their link rows go with them, {@code ON DELETE CASCADE}), which is what {@code OAuth2AuthorizationService.remove}
 * does too: refresh tokens and introspection answer "inactive" from the commit on.
 */
@Repository
@RequiredArgsConstructor
class JdbcSignInSessions implements SignInSessions {

    private static final int MAX_LISTED = 50;

    private final JdbcClient jdbc;

    @Override
    public List<Session> open(String userId, Instant idleSince, Instant now) {
        return jdbc.sql("""
                        SELECT s.id, s.device, s.city, host(s.ip) AS ip, s.method, s.created_at, s.last_seen_at,
                               coalesce(array_agg(DISTINCT c.client_name) FILTER (WHERE c.client_name IS NOT NULL),
                                        '{}') AS apps,
                               max(a.refresh_token_expires_at) AS refresh_until
                          FROM identity.sessions s
                          LEFT JOIN auth.authorization_sessions l ON l.session_id = s.id AND l.refreshable
                          LEFT JOIN auth.oauth2_authorization a
                                 ON a.id = l.authorization_id AND a.refresh_token_expires_at > :now
                          LEFT JOIN auth.oauth2_registered_client c ON c.id = a.registered_client_id
                         WHERE s.user_id = :u AND s.revoked_at IS NULL
                         GROUP BY s.id
                        HAVING coalesce(s.last_seen_at, s.created_at) > :idle OR max(a.refresh_token_expires_at) > :now
                         ORDER BY coalesce(s.last_seen_at, s.created_at) DESC, s.id DESC
                         LIMIT :limit
                        """)
                .param("u", userId)
                .param("idle", utc(idleSince))
                .param("now", utc(now))
                .param("limit", MAX_LISTED)
                .query((rs, _) -> new Session(
                        rs.getString("id"),
                        rs.getString("device"),
                        rs.getString("city"),
                        rs.getString("ip"),
                        rs.getString("method"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        instant(rs.getObject("last_seen_at", OffsetDateTime.class)),
                        strings(rs.getArray("apps"))))
                .list();
    }

    @Override
    public Optional<State> state(String sessionId) {
        return jdbc.sql("SELECT user_id, revoked_at IS NOT NULL AS ended FROM identity.sessions WHERE id = :id")
                .param("id", sessionId)
                .query((rs, _) -> new State(rs.getString("user_id"), rs.getBoolean("ended")))
                .optional();
    }

    @Override
    public void touch(String sessionId, Instant at, Instant notSince) {
        jdbc.sql("""
                        UPDATE identity.sessions SET last_seen_at = :at
                         WHERE id = :id AND revoked_at IS NULL AND (last_seen_at IS NULL OR last_seen_at < :since)
                        """)
                .param("id", sessionId)
                .param("at", utc(at))
                .param("since", utc(notSince))
                .update();
    }

    @Override
    public List<String> end(String userId, Collection<String> sessionIds, EndReason reason, Instant at) {
        if (sessionIds.isEmpty()) {
            return List.of();
        }
        var ended = jdbc.sql("""
                        UPDATE identity.sessions SET revoked_at = :at, revoke_reason = :reason
                         WHERE user_id = :u AND id IN (:ids) AND revoked_at IS NULL
                        RETURNING id
                        """)
                .param("u", userId)
                .param("ids", List.copyOf(sessionIds))
                .param("at", utc(at))
                .param("reason", reason.code())
                .query((rs, _) -> rs.getString("id"))
                .list();
        jdbc.sql("""
                        DELETE FROM auth.oauth2_authorization a
                         USING auth.authorization_sessions l
                         WHERE l.authorization_id = a.id AND l.session_id IN (:ids) AND a.principal_name = :u
                        """).param("u", userId).param("ids", List.copyOf(sessionIds)).update();
        return ended;
    }

    @Override
    public List<String> endAllExcept(String userId, Collection<String> keep, EndReason reason, Instant at) {
        var kept = keep.isEmpty() ? List.of("") : List.copyOf(keep); // IN () is not valid SQL
        var ended = jdbc.sql("""
                        UPDATE identity.sessions SET revoked_at = :at, revoke_reason = :reason
                         WHERE user_id = :u AND revoked_at IS NULL AND id NOT IN (:keep)
                        RETURNING id
                        """)
                .param("u", userId)
                .param("keep", kept)
                .param("at", utc(at))
                .param("reason", reason.code())
                .query((rs, _) -> rs.getString("id"))
                .list();
        jdbc.sql("""
                        DELETE FROM auth.oauth2_authorization a
                         WHERE a.principal_name = :u
                           AND NOT EXISTS (SELECT 1 FROM auth.authorization_sessions l
                                            WHERE l.authorization_id = a.id AND l.session_id IN (:keep))
                        """).param("u", userId).param("keep", kept).update();
        return ended;
    }

    @Override
    public void link(String authorizationId, String sessionId, boolean refreshable) {
        jdbc.sql("""
                        INSERT INTO auth.authorization_sessions (authorization_id, session_id, refreshable)
                        VALUES (:a, :s, :r)
                        ON CONFLICT (authorization_id) DO UPDATE SET refreshable = excluded.refreshable
                        """)
                .param("a", authorizationId)
                .param("s", sessionId)
                .param("r", refreshable)
                .update();
    }

    @Override
    public void deleteAuthorization(String authorizationId) {
        jdbc.sql("DELETE FROM auth.oauth2_authorization WHERE id = :id")
                .param("id", authorizationId)
                .update();
    }

    private static List<String> strings(@Nullable Array array) throws SQLException {
        return array == null
                ? List.of()
                : Arrays.stream((Object[]) array.getArray())
                        .map(String::valueOf)
                        .toList();
    }

    private static OffsetDateTime utc(Instant at) {
        return at.atOffset(ZoneOffset.UTC);
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
