package ca.northline.developer.persistence;

import ca.northline.developer.api.AuditLogQuery;
import ca.northline.shared.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@link AuditLogQuery} over {@code developer.audit_log}: keyset pages on (at, id), newest first. */
@Repository
@RequiredArgsConstructor
class AuditLogQueryJdbc implements AuditLogQuery {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    @Override
    public Page search(Filter f, int limit) {
        var params = new HashMap<String, Object>();
        var where = new StringBuilder(" where at is not null");
        if (f.actorId() != null) {
            where.append(" and actor_id = :actor");
            params.put("actor", f.actorId());
        }
        if (f.action() != null) {
            if (f.action().endsWith(".")) {
                where.append(" and action like :action");
                params.put("action", f.action().replace("%", "").replace("_", "\\_") + "%");
            } else {
                where.append(" and action = :action");
                params.put("action", f.action());
            }
        }
        if (f.merchantId() != null) {
            where.append(" and merchant_id = :merchant");
            params.put("merchant", f.merchantId());
        }
        if (f.targetId() != null) {
            where.append(" and target_id = :target");
            params.put("target", f.targetId());
        }
        if (f.from() != null) {
            where.append(" and at >= :from");
            params.put("from", JdbcTimes.ts(f.from()));
        }
        if (f.to() != null) {
            where.append(" and at < :to");
            params.put("to", JdbcTimes.ts(f.to()));
        }
        if (f.before() != null) {
            where.append(" and (at, id) < (:beforeAt, :beforeId)");
            params.put("beforeAt", JdbcTimes.ts(f.before().at()));
            params.put("beforeId", f.before().id());
        }
        params.put("limit", limit + 1);
        var rows = jdbc.sql("select * from developer.audit_log" + where + " order by at desc, id desc limit :limit")
                .params(params)
                .query((rs, _) -> entry(rs))
                .list();
        var more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        var last = items.isEmpty() ? null : items.getLast();
        return new Page(items, more && last != null ? new Cursor(last.at(), last.id()) : null);
    }

    private static Entry entry(ResultSet rs) throws SQLException {
        return new Entry(
                rs.getString("id"),
                JdbcTimes.requiredInstant(rs, "at"),
                rs.getString("actor_id"),
                rs.getString("role"),
                rs.getString("action"),
                rs.getString("target_type"),
                rs.getString("target_id"),
                rs.getString("merchant_id"),
                json(rs.getString("before")),
                json(rs.getString("after")));
    }

    private static @Nullable Map<String, Object> json(@Nullable String column) {
        return column == null ? null : JSON.readValue(column, new TypeReference<Map<String, Object>>() {});
    }
}
