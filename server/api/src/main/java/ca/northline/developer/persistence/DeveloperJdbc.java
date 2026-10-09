package ca.northline.developer.persistence;

import static ca.northline.shared.JdbcTimes.instant;
import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.developer.api.AuditTrail;
import ca.northline.developer.application.DeveloperStore;
import ca.northline.developer.domain.ApiKey;
import ca.northline.developer.domain.AuditRecord;
import ca.northline.developer.domain.WebhookDelivery;
import ca.northline.developer.domain.WebhookEndpoint;
import ca.northline.shared.Bytes;
import ca.northline.shared.Ids;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** {@link DeveloperStore} and the {@link AuditTrail} over schema {@code developer}. */
@Repository
@RequiredArgsConstructor
class DeveloperJdbc implements DeveloperStore, AuditTrail, ca.northline.developer.application.PlatformDeveloperStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;
    private final Clock clock;

    @Override
    public List<ApiKey> activeKeys(String merchantId) {
        return jdbc.sql("""
                        select * from developer.api_keys
                         where merchant_id = :m and revoked_at is null order by created_at, id
                        """).param("m", merchantId).query((rs, _) -> key(rs)).list();
    }

    @Override
    public Optional<ApiKey> findKey(String merchantId, String keyId) {
        return jdbc.sql("select * from developer.api_keys where merchant_id = :m and id = :id")
                .param("m", merchantId)
                .param("id", keyId)
                .query((rs, _) -> key(rs))
                .optional();
    }

    @Override
    public void insertKey(ApiKey key, byte[] hash, String createdBy) {
        jdbc.sql("""
                        insert into developer.api_keys
                               (id, merchant_id, name, scopes, key_hash, rate_limit, prefix, created_by, created_at)
                        values (:id, :m, :name, cast(:scopes as text[]), :hash, :rate, :prefix, :by, :at)
                        """)
                .param("id", key.id())
                .param("m", key.merchantId())
                .param("name", key.name())
                .param("scopes", pgArray(key.scopes()))
                .param("hash", hash)
                .param("rate", key.rateLimit())
                .param("prefix", key.prefix())
                .param("by", createdBy)
                .param("at", ts(key.createdAt()))
                .update();
    }

    @Override
    public void revokeKey(String keyId, Instant at) {
        jdbc.sql("update developer.api_keys set revoked_at = :at where id = :id and revoked_at is null")
                .param("id", keyId)
                .param("at", ts(at))
                .update();
    }

    @Override
    public List<WebhookEndpoint> endpoints(String merchantId) {
        return jdbc.sql(ENDPOINTS + " where e.merchant_id = :m order by e.created_at, e.id")
                .param("m", merchantId)
                .query((rs, _) -> endpoint(rs))
                .list();
    }

    @Override
    public Optional<WebhookEndpoint> findEndpoint(String merchantId, String endpointId) {
        return jdbc.sql(ENDPOINTS + " where e.merchant_id = :m and e.id = :id")
                .param("m", merchantId)
                .param("id", endpointId)
                .query((rs, _) -> endpoint(rs))
                .optional();
    }

    @Override
    public void insertEndpoint(WebhookEndpoint e, byte[] encryptedSecret, String secretRef, String createdBy) {
        jdbc.sql("""
                        insert into developer.webhook_endpoints
                               (id, merchant_id, url, secret_ref, secret_enc, events, active, created_by, created_at)
                        values (:id, :m, :url, :ref, :enc, cast(:events as text[]), :active, :by, :at)
                        """)
                .param("id", e.id())
                .param("m", e.merchantId())
                .param("url", e.url())
                .param("ref", secretRef)
                .param("enc", encryptedSecret)
                .param("events", pgArray(e.events()))
                .param("active", e.active())
                .param("by", createdBy)
                .param("at", ts(e.createdAt()))
                .update();
    }

    @Override
    public List<StoredSecrets> secretsToReencrypt(String currentRef, Instant now, int limit) {
        return jdbc.sql("""
                        select id, secret_ref, secret_enc,
                               case when secret_prev_until > :now then secret_prev_enc end as secret_prev_enc
                          from developer.webhook_endpoints
                         where secret_enc is not null
                           and (secret_ref is distinct from :current
                                or (secret_prev_enc is not null and secret_prev_until > :now))
                         order by created_at, id limit :limit""")
                .param("current", currentRef)
                .param("now", ts(now))
                .param("limit", limit)
                .query((rs, _) -> {
                    var previous = rs.getBytes("secret_prev_enc");
                    return new StoredSecrets(
                            rs.getString("id"),
                            rs.getString("secret_ref"),
                            Bytes.of(rs.getBytes("secret_enc")),
                            previous == null ? null : Bytes.of(previous));
                })
                .list();
    }

    @Override
    public int secretsUnderOtherKeys(String currentRef) {
        return jdbc.sql("""
                        select count(*) from developer.webhook_endpoints
                         where secret_enc is not null and secret_ref is distinct from :current""").param("current", currentRef).query(Integer.class).single();
    }

    @Override
    public boolean reencrypt(StoredSecrets read, Bytes secret, @Nullable Bytes previous, String currentRef) {
        return jdbc.sql("""
                        update developer.webhook_endpoints
                           set secret_enc = :enc, secret_ref = :ref,
                               secret_prev_enc = case when cast(:prev as bytea) is null then secret_prev_enc
                                                      else cast(:prev as bytea) end
                         where id = :id and secret_enc = :oldEnc and secret_ref is not distinct from :oldRef""")
                        .param("enc", secret.toArray())
                        .param("ref", currentRef)
                        .param("prev", previous == null ? null : previous.toArray(), java.sql.Types.BINARY)
                        .param("id", read.endpointId())
                        .param("oldEnc", read.secret().toArray())
                        .param("oldRef", read.secretRef(), java.sql.Types.VARCHAR)
                        .update()
                == 1;
    }

    @Override
    public void replaceSecret(
            String endpointId, byte[] encryptedSecret, String secretRef, @Nullable Instant previousUntil) {
        jdbc.sql("""
                        update developer.webhook_endpoints
                           set secret_prev_enc = case when cast(:until as timestamptz) is null then null else secret_enc end,
                               secret_prev_until = case when secret_enc is null then null else cast(:until as timestamptz) end,
                               secret_enc = :enc, secret_ref = :ref
                         where id = :id
                        """)
                .param("id", endpointId)
                .param("enc", encryptedSecret)
                .param("ref", secretRef)
                .param("until", previousUntil == null ? null : ts(previousUntil))
                .update();
    }

    @Override
    public void enableEndpoint(String endpointId) {
        jdbc.sql("""
                        update developer.webhook_endpoints
                           set active = true, disabled_at = null, disabled_reason = null, failing_since = null,
                               consecutive_failures = 0, disable_notice_id = null, disabled_notified_at = null
                         where id = :id
                        """).param("id", endpointId).update();
    }

    @Override
    public List<WebhookDelivery> deliveries(String endpointId, int limit) {
        var rows = jdbc.sql(DELIVERIES + " where d.endpoint_id = :e order by d.created_at desc, d.id desc limit :limit")
                .param("e", endpointId)
                .param("limit", limit)
                .query((rs, _) -> delivery(rs, List.of()))
                .list();
        if (rows.isEmpty()) {
            return rows;
        }
        var attempts = jdbc
                .sql("""
                        select delivery_id, attempt, at, status_code, duration_ms, error, response_snippet
                          from developer.webhook_attempts where delivery_id in (:ids)
                         order by delivery_id, attempt desc
                        """)
                .param("ids", rows.stream().map(WebhookDelivery::id).toList())
                .query((rs, _) -> Map.entry(rs.getString("delivery_id"), attempt(rs)))
                .list()
                .stream()
                .collect(Collectors.groupingBy(
                        Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        return rows.stream()
                .map(d -> withHistory(d, attempts.getOrDefault(d.id(), List.of())))
                .toList();
    }

    @Override
    public Optional<WebhookDelivery> findDelivery(String endpointId, String deliveryId) {
        return jdbc.sql(DELIVERIES + " where d.endpoint_id = :e and d.id = :id")
                .param("e", endpointId)
                .param("id", deliveryId)
                .query((rs, _) -> delivery(rs, List.of()))
                .optional();
    }

    @Override
    public WebhookDelivery queueDelivery(
            String id,
            String merchantId,
            String endpointId,
            String eventId,
            String eventType,
            @Nullable String resendOf,
            boolean test,
            Instant at) {
        jdbc.sql("""
                        insert into developer.webhook_deliveries
                               (id, endpoint_id, merchant_id, event_id, event_type, payload, state, attempt,
                                next_attempt_at, resend_of, test, created_at)
                        values (:id, :e, :m, :event, :type,
                                (select payload from developer.webhook_deliveries where id = :resendOf),
                                'pending', 0, :at, :resendOf, :test, :at)
                        """)
                .param("id", id)
                .param("e", endpointId)
                .param("m", merchantId)
                .param("event", eventId)
                .param("type", eventType)
                .param("resendOf", resendOf)
                .param("test", test)
                .param("at", ts(at))
                .update();
        return findDelivery(endpointId, id).orElseThrow();
    }

    @Override
    public void deleteEndpoint(String endpointId) {
        jdbc.sql("delete from developer.webhook_deliveries where endpoint_id = :id")
                .param("id", endpointId)
                .update();
        jdbc.sql("delete from developer.webhook_endpoints where id = :id")
                .param("id", endpointId)
                .update();
    }

    @Override
    public List<AuditRecord> audit(String merchantId, Instant since, int limit) {
        return jdbc.sql("""
                        select id, at, actor_id, role, action, target_type, target_id from developer.audit_log
                         where merchant_id = :m and at >= :since order by at desc, id desc limit :limit
                        """)
                .param("m", merchantId)
                .param("since", ts(since))
                .param("limit", limit)
                .query((rs, _) -> new AuditRecord(
                        rs.getString("id"),
                        requiredInstant(rs, "at"),
                        rs.getString("actor_id"),
                        rs.getString("role"),
                        rs.getString("action"),
                        rs.getString("target_type"),
                        rs.getString("target_id")))
                .list();
    }

    @Override
    public void record(Entry entry) {
        jdbc.sql("""
                        insert into developer.audit_log
                               (id, merchant_id, actor_id, role, action, target_type, target_id, before, after, at)
                        values (:id, :m, :actor, :role, :action, :type, :target,
                                cast(:before as jsonb), cast(:after as jsonb), :at)
                        """)
                .param("id", Ids.next())
                .param("m", entry.merchantId())
                .param("actor", entry.actorId())
                .param("role", entry.role())
                .param("action", entry.action())
                .param("type", entry.targetType())
                .param("target", entry.targetId())
                .param("before", json(entry.before()))
                .param("after", json(entry.after()))
                .param("at", ts(clock.instant()))
                .update();
    }

    private static final String ENDPOINTS = """
            select e.id, e.merchant_id, e.url, e.events, coalesce(e.active, true) as active, e.created_at,
                   e.failing_since, e.disabled_at,
                   case when e.secret_prev_enc is not null then e.secret_prev_until end as prev_until,
                   d.status_code as last_status, d.at as last_at
              from developer.webhook_endpoints e
              left join lateral (select status_code, at from developer.webhook_deliveries
                                  where endpoint_id = e.id order by at desc nulls last limit 1) d on true
            """;

    @Override
    public List<ApiKey> allKeys(int limit) {
        return jdbc.sql(
                        "select * from developer.api_keys order by revoked_at is not null, created_at desc, id limit :limit")
                .param("limit", limit)
                .query((rs, _) -> key(rs))
                .list();
    }

    @Override
    public Optional<ApiKey> keyById(String keyId) {
        return jdbc.sql("select * from developer.api_keys where id = :id")
                .param("id", keyId)
                .query((rs, _) -> key(rs))
                .optional();
    }

    private static ApiKey key(ResultSet rs) throws SQLException {
        return new ApiKey(
                rs.getString("id"),
                rs.getString("merchant_id"),
                rs.getString("name"),
                strings(rs.getArray("scopes")),
                rs.getString("prefix") == null ? "nl_live_" : rs.getString("prefix"),
                rs.getObject("rate_limit") == null ? ApiKey.DEFAULT_RATE_LIMIT : rs.getInt("rate_limit"),
                requiredInstant(rs, "created_at"),
                instant(rs, "last_used_at"),
                instant(rs, "revoked_at"));
    }

    private static WebhookEndpoint endpoint(ResultSet rs) throws SQLException {
        return new WebhookEndpoint(
                rs.getString("id"),
                rs.getString("merchant_id"),
                rs.getString("url"),
                strings(rs.getArray("events")),
                rs.getBoolean("active"),
                requiredInstant(rs, "created_at"),
                rs.getObject("last_status") == null ? null : rs.getInt("last_status"),
                instant(rs, "last_at"),
                instant(rs, "failing_since"),
                instant(rs, "disabled_at"),
                instant(rs, "prev_until"));
    }

    /** Deliveries; a row written before S-33 has no state: its status code says how it went. */
    private static final String DELIVERIES = """
            select d.id, d.endpoint_id, d.event_id, d.event_type,
                   coalesce(d.state, case when d.status_code between 200 and 299 then 'succeeded' else 'failed' end)
                       as state,
                   coalesce(d.attempt, 0) as attempts, d.status_code, d.at, d.next_attempt_at, d.duration_ms, d.error,
                   d.response_snippet, d.test, d.resend_of, least(d.created_at, coalesce(d.at, d.created_at)) as created_at
              from developer.webhook_deliveries d
            """;

    private static WebhookDelivery delivery(ResultSet rs, List<WebhookDelivery.Attempt> history) throws SQLException {
        var pending = WebhookDelivery.PENDING.equals(rs.getString("state"));
        return new WebhookDelivery(
                rs.getString("id"),
                rs.getString("endpoint_id"),
                rs.getString("event_id"),
                rs.getString("event_type"),
                rs.getString("state"),
                rs.getInt("attempts"),
                integer(rs, "status_code"),
                instant(rs, "at"),
                pending ? instant(rs, "next_attempt_at") : null,
                integer(rs, "duration_ms"),
                rs.getString("error"),
                rs.getString("response_snippet"),
                rs.getBoolean("test"),
                rs.getString("resend_of"),
                requiredInstant(rs, "created_at"),
                history);
    }

    private static WebhookDelivery withHistory(WebhookDelivery d, List<WebhookDelivery.Attempt> history) {
        return new WebhookDelivery(
                d.id(),
                d.endpointId(),
                d.eventId(),
                d.eventType(),
                d.state(),
                d.attempts(),
                d.statusCode(),
                d.lastAttemptAt(),
                d.nextAttemptAt(),
                d.durationMs(),
                d.error(),
                d.responseSnippet(),
                d.test(),
                d.resendOf(),
                d.createdAt(),
                history);
    }

    private static WebhookDelivery.Attempt attempt(ResultSet rs) throws SQLException {
        return new WebhookDelivery.Attempt(
                rs.getInt("attempt"),
                requiredInstant(rs, "at"),
                integer(rs, "status_code"),
                integer(rs, "duration_ms"),
                rs.getString("error"),
                rs.getString("response_snippet"));
    }

    private static @Nullable Integer integer(ResultSet rs, String column) throws SQLException {
        var value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static List<String> strings(@Nullable Array array) throws SQLException {
        return array == null
                ? List.of()
                : Arrays.stream((Object[]) array.getArray())
                        .map(String::valueOf)
                        .toList();
    }

    private static String pgArray(List<String> values) {
        return values.stream()
                .map(v -> '"' + v.replace("\\", "\\\\").replace("\"", "\\\"") + '"')
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static @Nullable String json(@Nullable Map<String, ?> value) {
        return value == null ? null : JSON.writeValueAsString(value);
    }
}
