package ca.northline.messaging.persistence;

import ca.northline.messaging.application.CustomerNotifications.CustomerPrefsStore;
import ca.northline.messaging.domain.CustomerNotificationPrefs;
import java.time.LocalTime;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link CustomerPrefsStore}: {@code messaging.notification_prefs.customer_matrix}, {@code quiet_on}, {@code notify_lang},
 * {@code marketing} (V162) plus the person's shared {@code quiet_from} / {@code quiet_to}. Writing never touches the
 * Studio member matrix.
 */
@Repository
@RequiredArgsConstructor
class CustomerPrefsAdapter implements CustomerPrefsStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Map<String, Boolean>>> MATRIX = new TypeReference<>() {};

    private final JdbcClient jdbc;

    @Override
    public Optional<CustomerNotificationPrefs> find(String userId) {
        return jdbc.sql("""
                        select customer_matrix::text as matrix, quiet_on, quiet_from, quiet_to, notify_lang, marketing
                          from messaging.notification_prefs where user_id = :u
                        """)
                .param("u", userId)
                .query((rs, _) -> {
                    var text = rs.getString("matrix");
                    return CustomerNotificationPrefs.of(
                            text == null ? Map.of() : JSON.readValue(text, MATRIX),
                            rs.getObject("quiet_on", Boolean.class),
                            rs.getObject("quiet_from", LocalTime.class),
                            rs.getObject("quiet_to", LocalTime.class),
                            rs.getString("notify_lang"),
                            rs.getString("marketing"));
                })
                .optional();
    }

    @Override
    public void save(String userId, CustomerNotificationPrefs p) {
        jdbc.sql("""
                        insert into messaging.notification_prefs (user_id, customer_matrix, quiet_on, quiet_from, quiet_to,
                               notify_lang, marketing, updated_at)
                        values (:u, cast(:matrix as jsonb), :on, :from, :to, :lang, :mkt, now())
                        on conflict (user_id) do update set customer_matrix = excluded.customer_matrix,
                               quiet_on = excluded.quiet_on, quiet_from = excluded.quiet_from, quiet_to = excluded.quiet_to,
                               notify_lang = excluded.notify_lang, marketing = excluded.marketing,
                               updated_at = excluded.updated_at
                        """)
                .param("u", userId)
                .param("matrix", JSON.writeValueAsString(p.matrix()))
                .param("on", p.quietOn())
                .param("from", p.quietFrom())
                .param("to", p.quietTo())
                .param("lang", p.language())
                .param("mkt", p.marketing())
                .update();
    }
}
