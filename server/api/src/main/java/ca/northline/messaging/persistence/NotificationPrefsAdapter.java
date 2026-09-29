package ca.northline.messaging.persistence;

import ca.northline.messaging.application.NotificationPreferences.NotificationPrefsStore;
import ca.northline.messaging.domain.NotificationMatrix;
import java.time.LocalTime;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@link NotificationPrefsStore} over {@code messaging.notification_prefs} (matrix jsonb, quiet hours). */
@Repository
@RequiredArgsConstructor
class NotificationPrefsAdapter implements NotificationPrefsStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Map<String, Boolean>>> MATRIX = new TypeReference<>() {};

    private final JdbcClient jdbc;

    @Override
    public Optional<NotificationMatrix> find(String userId) {
        return jdbc.sql("""
                        select matrix::text as matrix, quiet_from, quiet_to from messaging.notification_prefs
                         where user_id = :u
                        """)
                .param("u", userId)
                .query((rs, _) -> {
                    var text = rs.getString("matrix");
                    var from = rs.getObject("quiet_from", LocalTime.class);
                    var to = rs.getObject("quiet_to", LocalTime.class);
                    return NotificationMatrix.withDefaults(
                            text == null ? Map.of() : JSON.readValue(text, MATRIX),
                            from == null ? NotificationMatrix.QUIET_FROM : from,
                            to == null ? NotificationMatrix.QUIET_TO : to);
                })
                .optional();
    }

    @Override
    public void save(String userId, NotificationMatrix matrix) {
        jdbc.sql("""
                        insert into messaging.notification_prefs (user_id, matrix, quiet_from, quiet_to, updated_at)
                        values (:u, cast(:matrix as jsonb), :from, :to, now())
                        on conflict (user_id) do update set matrix = excluded.matrix, quiet_from = excluded.quiet_from,
                               quiet_to = excluded.quiet_to, updated_at = excluded.updated_at
                        """)
                .param("u", userId)
                .param("matrix", JSON.writeValueAsString(matrix.matrix()))
                .param("from", matrix.quietFrom())
                .param("to", matrix.quietTo())
                .update();
    }
}
