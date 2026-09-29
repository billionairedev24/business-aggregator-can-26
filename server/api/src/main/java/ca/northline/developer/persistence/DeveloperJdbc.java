package ca.northline.developer.persistence;

import static ca.northline.shared.JdbcTimes.instant;
import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.developer.api.AuditTrail;
import ca.northline.developer.application.DeveloperStore;
import ca.northline.developer.domain.ApiKey;
import ca.northline.developer.domain.AuditRecord;
import ca.northline.developer.domain.WebhookEndpoint;
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
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** {@link DeveloperStore} and the {@link AuditTrail} over schema {@code developer}. */
@Repository
@RequiredArgsConstructor
class DeveloperJdbc implements DeveloperStore, AuditTrail {

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
    public void replaceSecret(String endpointId, byte[] encryptedSecret, String secretRef) {
        jdbc.sql("update developer.webhook_endpoints set secret_enc = :enc, secret_ref = :ref where id = :id")
                .param("id", endpointId)
                .param("enc", encryptedSecret)
                .param("ref", secretRef)
                .update();
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
                   d.status_code as last_status, d.at as last_at
              from developer.webhook_endpoints e
              left join lateral (select status_code, at from developer.webhook_deliveries
                                  where endpoint_id = e.id order by at desc nulls last limit 1) d on true
            """;

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
                instant(rs, "last_at"));
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
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    private static @Nullable String json(@Nullable Map<String, ?> value) {
        return value == null ? null : JSON.writeValueAsString(value);
    }
}
