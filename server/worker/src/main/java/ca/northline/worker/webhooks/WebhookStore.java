package ca.northline.worker.webhooks;

import com.github.f4b6a3.ulid.UlidCreator;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The worker's side of {@code developer.webhook_endpoints}, {@code webhook_deliveries} and {@code webhook_attempts}
 * (V015, V083, V087 — the api's developer module owns the schema and writes endpoints, resends and test events). One
 * statement per step; the dispatcher decides the transactions.
 */
public final class WebhookStore {

    /** What the dispatcher needs to send to an endpoint, read fresh before each delivery (never compared). */
    @SuppressWarnings("ArrayRecordComponent")
    public record Target(
            String id,
            String merchantId,
            String url,
            boolean active,
            byte @Nullable [] secret,
            byte @Nullable [] previousSecret,
            @Nullable Instant previousUntil) {}

    /** The next delivery due for an endpoint. {@code payload} is null for a test event not sent yet. */
    public record Due(
            String id,
            String eventId,
            String eventType,
            @Nullable String payload,
            int attempts,
            boolean test,
            Instant createdAt) {}

    /** The endpoint's health after a failed attempt. */
    public record Health(int consecutiveFailures, Instant failingSince) {}

    /** An endpoint the worker turned off whose owners haven't been emailed yet. */
    public record DisabledEndpoint(
            String id,
            String merchantId,
            String url,
            String noticeId,
            Instant failingSince,
            Instant disabledAt,
            @Nullable String lastError) {}

    private final JdbcClient jdbc;

    public WebhookStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ── Fan-out (consumer transaction) ────────────────────────────────────────────────────────────────────────────

    /** Active endpoints of the business subscribed to {@code type}. */
    public List<String> subscribers(String merchantId, String type) {
        return jdbc.sql("""
                        select id from developer.webhook_endpoints
                         where merchant_id = :m and active and :type = any(events)
                         order by id""")
                .param("m", merchantId)
                .param("type", type)
                .query((rs, _) -> rs.getString(1))
                .list();
    }

    /** Queues the event for the endpoint, due now; a second call for the same (endpoint, event) adds nothing. */
    public boolean queue(String endpointId, WebhookPayloads.PublicEvent event, Instant at) {
        return jdbc.sql("""
                        insert into developer.webhook_deliveries
                               (id, endpoint_id, merchant_id, event_id, event_type, payload, state, attempt,
                                next_attempt_at, created_at)
                        values (:id, :e, :m, :event, :type, :payload, 'pending', 0, :at, :at)
                        on conflict (endpoint_id, event_id) where resend_of is null and not test do nothing""")
                        .param("id", UlidCreator.getMonotonicUlid().toString())
                        .param("e", endpointId)
                        .param("m", event.merchantId())
                        .param("event", event.eventId())
                        .param("type", event.type())
                        .param("payload", event.payload().toString())
                        .param("at", ts(at))
                        .update()
                == 1;
    }

    // ── Scheduling: one lease per endpoint ─────────────────────────────────────────────────────────────────────────

    /** Active, unleased endpoints with a delivery due, the longest-waiting first. */
    public List<String> dueEndpoints(Instant now, int limit) {
        return jdbc.sql("""
                        select e.id
                          from developer.webhook_endpoints e
                          join lateral (select min(d.next_attempt_at) as due from developer.webhook_deliveries d
                                         where d.endpoint_id = e.id and d.state = 'pending'
                                           and d.next_attempt_at <= :now) q on q.due is not null
                         where e.active and (e.lease_until is null or e.lease_until < :now)
                         order by q.due, e.id
                         limit :limit""")
                .param("now", ts(now))
                .param("limit", limit)
                .query((rs, _) -> rs.getString(1))
                .list();
    }

    /** Takes the endpoint for {@code owner} until {@code until}; false when another replica holds it. */
    public boolean lease(String endpointId, String owner, Instant now, Instant until) {
        return jdbc.sql("""
                        update developer.webhook_endpoints set lease_owner = :owner, lease_until = :until
                         where id = :id and active and (lease_until is null or lease_until < :now)""")
                        .param("id", endpointId)
                        .param("owner", owner)
                        .param("now", ts(now))
                        .param("until", ts(until))
                        .update()
                == 1;
    }

    /** Extends a lease this owner still holds; false when it was lost (expired and taken). */
    public boolean renew(String endpointId, String owner, Instant until) {
        return jdbc.sql("""
                        update developer.webhook_endpoints set lease_until = :until
                         where id = :id and lease_owner = :owner""")
                        .param("id", endpointId)
                        .param("owner", owner)
                        .param("until", ts(until))
                        .update()
                == 1;
    }

    public void release(String endpointId, String owner) {
        jdbc.sql("""
                        update developer.webhook_endpoints set lease_owner = null, lease_until = null
                         where id = :id and lease_owner = :owner""").param("id", endpointId).param("owner", owner).update();
    }

    public Optional<Due> nextDue(String endpointId, Instant now) {
        return jdbc.sql("""
                        select id, event_id, event_type, payload, attempt, test, created_at
                          from developer.webhook_deliveries
                         where endpoint_id = :e and state = 'pending' and next_attempt_at <= :now
                         order by next_attempt_at, id
                         limit 1""")
                .param("e", endpointId)
                .param("now", ts(now))
                .query((rs, _) -> new Due(
                        rs.getString("id"),
                        rs.getString("event_id"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getInt("attempt"),
                        rs.getBoolean("test"),
                        instant(rs, "created_at")))
                .optional();
    }

    public Optional<Target> target(String endpointId) {
        return jdbc.sql("""
                        select id, merchant_id, url, coalesce(active, true) as active, secret_enc, secret_prev_enc,
                               secret_prev_until
                          from developer.webhook_endpoints where id = :id""")
                .param("id", endpointId)
                .query((rs, _) -> new Target(
                        rs.getString("id"),
                        rs.getString("merchant_id"),
                        rs.getString("url"),
                        rs.getBoolean("active"),
                        rs.getBytes("secret_enc"),
                        rs.getBytes("secret_prev_enc"),
                        nullableInstant(rs, "secret_prev_until")))
                .optional();
    }

    /** A test event's payload, written when it is first sent (so the log shows what went out). */
    public void setPayload(String deliveryId, String payload) {
        jdbc.sql("update developer.webhook_deliveries set payload = :p where id = :id and payload is null")
                .param("id", deliveryId)
                .param("p", payload)
                .update();
    }

    // ── Outcomes (one transaction per attempt) ─────────────────────────────────────────────────────────────────────

    public void recordAttempt(String deliveryId, int attempt, Instant at, WebhookTransport.Result result) {
        jdbc.sql("""
                        insert into developer.webhook_attempts
                               (id, delivery_id, attempt, at, status_code, duration_ms, error, response_snippet)
                        select :id, :d, :n, :at, :status, :ms, :error, :snippet
                         where exists (select 1 from developer.webhook_deliveries where id = :d)
                        on conflict (delivery_id, attempt) do nothing""")
                .param("id", UlidCreator.getMonotonicUlid().toString())
                .param("d", deliveryId)
                .param("n", attempt)
                .param("at", ts(at))
                .param("status", result.status())
                .param("ms", result.durationMs())
                .param("error", result.error())
                .param("snippet", result.snippet())
                .update();
    }

    public void succeeded(
            String deliveryId, String endpointId, int attempt, Instant at, WebhookTransport.Result result) {
        outcome(deliveryId, "succeeded", attempt, at, result, null);
        jdbc.sql("""
                        update developer.webhook_endpoints set failing_since = null, consecutive_failures = 0
                         where id = :id and (failing_since is not null or consecutive_failures > 0)""").param("id", endpointId).update();
    }

    /** Records a failed attempt: retried at {@code next}, or failed for good when null. Returns the endpoint's health. */
    public Optional<Health> failed(
            String deliveryId,
            String endpointId,
            int attempt,
            Instant at,
            WebhookTransport.Result result,
            @Nullable Instant next) {
        outcome(deliveryId, next == null ? "failed" : "pending", attempt, at, result, next);
        return jdbc.sql("""
                        update developer.webhook_endpoints
                           set consecutive_failures = consecutive_failures + 1,
                               failing_since = coalesce(failing_since, :at)
                         where id = :id
                        returning consecutive_failures, failing_since""")
                .param("id", endpointId)
                .param("at", ts(at))
                .query((rs, _) -> new Health(rs.getInt("consecutive_failures"), instant(rs, "failing_since")))
                .optional();
    }

    /**
     * Turns the endpoint off after sustained failure: its pending deliveries fail (they stay in the log for a resend),
     * and the audit log records it. False when it was already off (another replica, or the owner deleted it).
     */
    public boolean disable(String endpointId, String noticeId, Instant at, String lastOutcome) {
        var merchant = jdbc.sql("""
                        update developer.webhook_endpoints
                           set active = false, disabled_at = :at, disabled_reason = 'failing', disable_notice_id = :n,
                               disabled_notified_at = null
                         where id = :id and active
                        returning merchant_id""")
                .param("id", endpointId)
                .param("n", noticeId)
                .param("at", ts(at))
                .query(String.class)
                .optional();
        if (merchant.isEmpty()) {
            return false;
        }
        jdbc.sql("""
                        update developer.webhook_deliveries
                           set state = 'failed', next_attempt_at = null,
                               error = case when attempt = 0 then 'endpoint turned off' else error end
                         where endpoint_id = :id and state = 'pending'""").param("id", endpointId).update();
        jdbc.sql("""
                        insert into developer.audit_log
                               (id, merchant_id, actor_id, role, action, target_type, target_id, after, at)
                        values (:id, :m, null, 'system', 'webhook.disabled', 'webhook_endpoint', :e,
                                jsonb_build_object('reason', 'failing', 'lastOutcome', cast(:outcome as text)), :at)""")
                .param("id", UlidCreator.getMonotonicUlid().toString())
                .param("m", merchant.get())
                .param("e", endpointId)
                .param("outcome", lastOutcome)
                .param("at", ts(at))
                .update();
        return true;
    }

    // ── Owners' email ──────────────────────────────────────────────────────────────────────────────────────────────

    /** Endpoints turned off since {@code since} whose owners haven't been told yet. */
    public List<DisabledEndpoint> unnotified(Instant since, int limit) {
        return jdbc.sql("""
                        select e.id, e.merchant_id, e.url, e.disable_notice_id, e.failing_since, e.disabled_at,
                               (select coalesce(a.error, 'HTTP ' || a.status_code)
                                  from developer.webhook_attempts a
                                  join developer.webhook_deliveries d on d.id = a.delivery_id
                                 where d.endpoint_id = e.id order by a.at desc, a.id desc limit 1) as last_error
                          from developer.webhook_endpoints e
                         where not e.active and e.disabled_reason = 'failing' and e.disabled_notified_at is null
                           and e.disable_notice_id is not null and e.disabled_at >= :since
                         order by e.disabled_at
                         limit :limit""")
                .param("since", ts(since))
                .param("limit", limit)
                .query((rs, _) -> new DisabledEndpoint(
                        rs.getString("id"),
                        rs.getString("merchant_id"),
                        rs.getString("url"),
                        rs.getString("disable_notice_id"),
                        Optional.ofNullable(nullableInstant(rs, "failing_since"))
                                .orElse(instant(rs, "disabled_at")),
                        instant(rs, "disabled_at"),
                        rs.getString("last_error")))
                .list();
    }

    public void notified(String endpointId, Instant at) {
        jdbc.sql("update developer.webhook_endpoints set disabled_notified_at = :at where id = :id")
                .param("id", endpointId)
                .param("at", ts(at))
                .update();
    }

    /** Deletes finished deliveries (and their attempts) created before {@code before}; returns how many. */
    public int purge(Instant before) {
        return jdbc.sql("""
                        delete from developer.webhook_deliveries
                         where created_at < :before and state in ('succeeded', 'failed')""").param("before", ts(before)).update();
    }

    private void outcome(
            String deliveryId,
            String state,
            int attempt,
            Instant at,
            WebhookTransport.Result result,
            @Nullable Instant next) {
        jdbc.sql("""
                        update developer.webhook_deliveries
                           set state = :state, attempt = :n, at = :at, status_code = :status, duration_ms = :ms,
                               error = :error, response_snippet = :snippet, next_attempt_at = :next
                         where id = :id""")
                .param("id", deliveryId)
                .param("state", state)
                .param("n", attempt)
                .param("at", ts(at))
                .param("status", result.status())
                .param("ms", result.durationMs())
                .param("error", result.error())
                .param("snippet", result.snippet())
                .param("next", next == null ? null : ts(next))
                .update();
    }

    private static OffsetDateTime ts(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static @Nullable Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
