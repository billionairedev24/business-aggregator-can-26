package ca.northline.trust.persistence;

import ca.northline.shared.JdbcTimes;
import ca.northline.shared.MerchantScope;
import ca.northline.trust.application.TrustFlagStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Open flags in {@code trust.flags}; a second raise for the same target and rule is a no-op (listener retries). */
@Repository
@RequiredArgsConstructor
class TrustFlagAdapter implements TrustFlagStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String COLUMNS = """
            id, target_type, target_id, rule, merchant_id, coalesce(state, 'open') as state, evidence, created_at,
            decided_by, decided_at, decision_note, action""";

    private final JdbcClient jdbc;

    @Override
    public void raise(
            String id,
            String targetType,
            String targetId,
            String rule,
            String merchantId,
            String actorId,
            Map<String, String> evidence) {
        jdbc.sql("""
                        insert into trust.flags (id, target_type, target_id, rule, evidence, state, actor_id, merchant_id)
                        select :id, :type, :target, :rule, cast(:evidence as jsonb), 'open', :actor, :m
                         where not exists (select 1 from trust.flags
                                            where target_type = :type and target_id = :target and rule = :rule)
                        """)
                .param("id", id)
                .param("type", targetType)
                .param("target", targetId)
                .param("rule", rule)
                .param("evidence", JSON.writeValueAsString(evidence))
                .param("actor", actorId)
                .param("m", merchantId)
                .update();
    }

    @Override
    public boolean raiseUnlessOpen(
            String id,
            String targetType,
            String targetId,
            String rule,
            @Nullable String merchantId,
            String actorId,
            Map<String, String> evidence) {
        return jdbc.sql("""
                        insert into trust.flags (id, target_type, target_id, rule, evidence, state, actor_id, merchant_id)
                        select :id, :type, :target, :rule, cast(:evidence as jsonb), 'open', :actor, :m
                         where not exists (select 1 from trust.flags
                                            where target_type = :type and target_id = :target and rule = :rule
                                              and coalesce(state, 'open') = 'open')
                        """)
                        .param("id", id)
                        .param("type", targetType)
                        .param("target", targetId)
                        .param("rule", rule)
                        .param("evidence", JSON.writeValueAsString(evidence))
                        .param("actor", actorId)
                        .param("m", merchantId)
                        .update()
                > 0;
    }

    @Override
    public List<StoredFlag> list(@Nullable String state, @Nullable String source, int limit) {
        return jdbc.sql("select " + COLUMNS + """
                         from trust.flags
                         where (cast(:state as text) is null or coalesce(state, 'open') = cast(:state as text))
                           and (cast(:source as text) is null
                                or (cast(:source as text) = 'ai') = (coalesce(evidence ->> 'source', '') = 'ai'))
                         order by created_at desc, id desc
                         limit :limit
                        """)
                .param("state", state)
                .param("source", source)
                .param("limit", limit)
                .query((rs, n) -> flag(rs))
                .list();
    }

    @Override
    public Optional<StoredFlag> find(String id) {
        return jdbc.sql("select " + COLUMNS + " from trust.flags where id = :id")
                .param("id", id)
                .query((rs, n) -> flag(rs))
                .optional();
    }

    @Override
    public List<StoredFlag> queue(MerchantScope scope, Instant since, int limit) {
        return jdbc.sql("select " + COLUMNS + """
                         from trust.flags
                         where (:everyone or merchant_id = any(:merchants))
                           and (coalesce(state, 'open') = 'open' or decided_at >= :since)
                         order by coalesce(state, 'open') = 'open' desc, created_at, decided_at desc, id
                         limit :limit
                        """)
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .param("since", since.atOffset(ZoneOffset.UTC))
                .param("limit", limit)
                .query((rs, n) -> flag(rs))
                .list();
    }

    @Override
    public List<StoredFlag> openOn(String targetType, @Nullable String targetId, int limit) {
        return jdbc.sql("select " + COLUMNS + """
                         from trust.flags
                         where coalesce(state, 'open') = 'open' and target_type = :type
                           and (cast(:target as text) is null or target_id = cast(:target as text))
                         order by created_at, id
                         limit :limit
                        """)
                .param("type", targetType)
                .param("target", targetId)
                .param("limit", limit)
                .query((rs, n) -> flag(rs))
                .list();
    }

    @Override
    public boolean decide(String id, String state, String action, String staffId, @Nullable String note, Instant at) {
        return jdbc.sql("""
                        update trust.flags
                           set state = :state, action = :action, decided_by = :staff, decided_at = :at,
                               decision_note = :note
                         where id = :id and coalesce(state, 'open') = 'open'
                        """)
                        .param("state", state)
                        .param("action", action)
                        .param("staff", staffId)
                        .param("at", at.atOffset(ZoneOffset.UTC))
                        .param("note", note)
                        .param("id", id)
                        .update()
                > 0;
    }

    private static StoredFlag flag(ResultSet rs) throws SQLException {
        var evidence = new LinkedHashMap<String, String>();
        var raw = rs.getString("evidence");
        if (raw != null) {
            var node = JSON.readTree(raw);
            for (var e : node.properties()) {
                var v = e.getValue();
                evidence.put(e.getKey(), v.isString() ? v.asString() : v.toString());
            }
        }
        return new StoredFlag(
                rs.getString("id"),
                String.valueOf(rs.getString("target_type")),
                String.valueOf(rs.getString("target_id")),
                String.valueOf(rs.getString("rule")),
                rs.getString("merchant_id"),
                rs.getString("state"),
                evidence,
                JdbcTimes.requiredInstant(rs, "created_at"),
                rs.getString("decided_by"),
                JdbcTimes.instant(rs, "decided_at"),
                rs.getString("decision_note"),
                rs.getString("action"));
    }
}
