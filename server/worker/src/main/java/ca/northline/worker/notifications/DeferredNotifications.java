package ca.northline.worker.notifications;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code messaging.deferred_notifications} (V074; {@code audience} V245): SMS and push held back by a team member's or
 * a customer's quiet hours, one row per (channel, person, event) — deferring twice (a redelivered event) keeps one row.
 */
public final class DeferredNotifications {

    /** One held-back notification. */
    public record Deferred(
            String id,
            Channel channel,
            String audience,
            String userId,
            @Nullable String merchantId,
            String eventId,
            String eventType,
            int eventVersion,
            JsonNode payload,
            int attempts) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public DeferredNotifications(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void defer(String id, Notice notice, Recipient to, Channel channel, Instant dueAt) {
        jdbc.sql("""
                        insert into messaging.deferred_notifications
                               (id, channel, audience, user_id, merchant_id, event_id, event_type, event_version,
                                payload, due_at)
                        values (:id, :channel, :audience, :user, :merchant, :event, :type, :version,
                                cast(:payload as jsonb), :due)
                        on conflict (channel, user_id, event_id) do nothing""")
                .param("id", id)
                .param("channel", channel.code())
                .param("audience", audience(notice.audience()))
                .param("user", to.userId())
                .param("merchant", notice.merchantId())
                .param("event", notice.eventId())
                .param("type", notice.type())
                .param("version", notice.version())
                .param("payload", json.writeValueAsString(notice.payload()))
                .param("due", at(dueAt))
                .update();
    }

    /** Due rows, locked for this transaction ({@code skip locked}: replicas share the work). */
    public List<Deferred> lockDue(Instant now, int limit) {
        return jdbc.sql("""
                        select id, channel, audience, user_id, merchant_id, event_id, event_type, event_version, payload::text,
                               attempts
                          from messaging.deferred_notifications
                         where due_at <= :now
                         order by due_at
                         limit :limit
                           for update skip locked""")
                .param("now", at(now))
                .param("limit", limit)
                .query((rs, _) -> new Deferred(
                        rs.getString("id"),
                        Channel.valueOf(rs.getString("channel").toUpperCase(java.util.Locale.ROOT)),
                        rs.getString("audience"),
                        rs.getString("user_id"),
                        rs.getString("merchant_id"),
                        rs.getString("event_id"),
                        rs.getString("event_type"),
                        rs.getInt("event_version"),
                        json.readTree(rs.getString("payload")),
                        rs.getInt("attempts")))
                .list();
    }

    public void done(String id) {
        jdbc.sql("delete from messaging.deferred_notifications where id = :id")
                .param("id", id)
                .update();
    }

    public void retryAt(String id, Instant dueAt) {
        jdbc.sql("update messaging.deferred_notifications set attempts = attempts + 1, due_at = :due where id = :id")
                .param("id", id)
                .param("due", at(dueAt))
                .update();
    }

    public int pending(String userId) {
        return jdbc.sql("select count(*) from messaging.deferred_notifications where user_id = :u")
                .param("u", userId)
                .query(Integer.class)
                .single();
    }

    static String audience(Notice.Audience audience) {
        return switch (audience) {
            case Notice.Audience.Team _ -> "team";
            case Notice.Audience.Customer _ -> "customer";
            case Notice.Audience.Courier _ -> "courier";
        };
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
