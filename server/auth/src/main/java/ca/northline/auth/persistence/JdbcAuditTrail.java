package ca.northline.auth.persistence;

import ca.northline.auth.application.AuditTrail;
import com.github.f4b6a3.ulid.UlidCreator;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** {@code developer.audit_log} rows written in the caller's transaction (role {@code user}: the person themselves). */
@Repository
@RequiredArgsConstructor
class JdbcAuditTrail implements AuditTrail {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final JsonMapper json;

    @Override
    public void record(String actorId, String action, String targetType, String targetId, Map<String, ?> after) {
        jdbc.sql("""
                        INSERT INTO developer.audit_log (id, actor_id, role, action, target_type, target_id, after, at)
                        VALUES (:id, :actor, 'user', :action, :type, :target, CAST(:after AS jsonb), :at)
                        """)
                .param("id", UlidCreator.getMonotonicUlid().toString())
                .param("actor", actorId)
                .param("action", action)
                .param("type", targetType)
                .param("target", targetId)
                .param("after", json.writeValueAsString(after))
                .param("at", clock.instant().atOffset(ZoneOffset.UTC))
                .update();
    }
}
