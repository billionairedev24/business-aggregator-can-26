package ca.northline.worker.notifications;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code messaging.deferred_notifications} (V074; {@code audience} V245; {@code dead_at}, {@code last_error} V290): SMS
 * and push held back by a team member's or a customer's quiet hours, or retried after a provider outage, one row per
 * (channel, person, event) — deferring twice (a redelivered event) keeps one row. A row given up after its attempts
 * stays as <b>dead</b> (the table's dead-letter queue, S-115) until an operator requeues it ({@code DlqReplayCommand
 * --deferred}) or the nightly purge removes it.
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

    /**
     * A dead row (given up), for the operator.
     *
     * @param lastError the failure's class (no message: provider messages can carry phone numbers)
     */
    public record Dead(
            String id,
            String channel,
            String audience,
            String userId,
            String eventId,
            String eventType,
            int attempts,
            Instant deadAt,
            @Nullable String lastError) {}

    /** Which dead rows; empty = any. {@code since}/{@code until} are on {@code dead_at}. */
    public record DeadFilter(
            Optional<String> channel,
            Optional<String> type,
            Optional<String> eventId,
            Optional<Instant> since,
            Optional<Instant> until) {}

    /** Due rows, locked for this transaction ({@code skip locked}: replicas share the work). */
    public List<Deferred> lockDue(Instant now, int limit) {
        return jdbc.sql("""
                        select id, channel, audience, user_id, merchant_id, event_id, event_type, event_version, payload::text,
                               attempts
                          from messaging.deferred_notifications
                         where due_at <= :now and dead_at is null
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

    /** Gives a row up: it stays, dead, for an operator to requeue (or the purge to remove). */
    public void dead(String id, Instant at, String error) {
        jdbc.sql("""
                        update messaging.deferred_notifications
                           set attempts = attempts + 1, dead_at = :at, last_error = :error where id = :id""").param("id", id).param("at", at(at)).param("error", error).update();
    }

    /** Dead rows matching the filter, oldest first. */
    public List<Dead> dead(DeadFilter filter, int limit) {
        return jdbc.sql("""
                        select id, channel, audience, user_id, event_id, event_type, attempts, dead_at, last_error
                          from messaging.deferred_notifications
                         where dead_at is not null
                           and (cast(:channel as text) is null or channel = :channel)
                           and (cast(:type as text) is null or event_type = :type)
                           and (cast(:event as text) is null or event_id = :event)
                           and (cast(:since as timestamptz) is null or dead_at >= :since)
                           and (cast(:until as timestamptz) is null or dead_at < :until)
                         order by dead_at, id
                         limit :limit""")
                .param("channel", filter.channel().orElse(null))
                .param("type", filter.type().orElse(null))
                .param("event", filter.eventId().orElse(null))
                .param("since", filter.since().map(DeferredNotifications::at).orElse(null))
                .param("until", filter.until().map(DeferredNotifications::at).orElse(null))
                .param("limit", limit)
                .query((rs, _) -> new Dead(
                        rs.getString("id"),
                        rs.getString("channel"),
                        rs.getString("audience"),
                        rs.getString("user_id"),
                        rs.getString("event_id"),
                        rs.getString("event_type"),
                        rs.getInt("attempts"),
                        rs.getObject("dead_at", OffsetDateTime.class).toInstant(),
                        rs.getString("last_error")))
                .list();
    }

    /**
     * Brings dead rows back: due at {@code dueAt} with fresh attempts; the job sends them (re-reading the person and
     * their matrix first). Returns how many were dead and are pending again.
     */
    public int requeue(List<String> ids, Instant dueAt) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                        update messaging.deferred_notifications
                           set dead_at = null, last_error = null, attempts = 0, due_at = :due
                         where id in (:ids) and dead_at is not null""").param("ids", ids).param("due", at(dueAt)).update();
    }

    /** Deletes dead rows given up before {@code before}; returns how many. */
    public int purgeDead(Instant before) {
        return jdbc.sql("delete from messaging.deferred_notifications where dead_at < :before")
                .param("before", at(before))
                .update();
    }

    /** Dead rows per channel (one grouped query; engineering follow-ups: the dead-letter gauge). */
    public Map<Channel, Integer> deadCounts() {
        var counts = new EnumMap<Channel, Integer>(Channel.class);
        jdbc.sql("""
                        select channel, count(*) as n from messaging.deferred_notifications
                         where dead_at is not null group by channel""")
                .query((rs, _) -> Map.entry(rs.getString("channel"), rs.getInt("n")))
                .list()
                .forEach(e -> Arrays.stream(Channel.values())
                        .filter(c -> c.code().equals(e.getKey()))
                        .findFirst()
                        .ifPresent(c -> counts.put(c, e.getValue())));
        return counts;
    }

    public int pending(String userId) {
        return jdbc.sql("select count(*) from messaging.deferred_notifications where user_id = :u and dead_at is null")
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
