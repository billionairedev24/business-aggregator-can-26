package ca.northline.trust.persistence;

import ca.northline.shared.MerchantScope;
import ca.northline.trust.application.TrustRuleStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@link TrustRuleStore} over {@code trust.rules} (V213) and {@code trust.reviews}. */
@Repository
@RequiredArgsConstructor
class TrustRuleJdbc implements TrustRuleStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};

    private final JdbcClient jdbc;

    @Override
    public Map<String, Stored> all() {
        return jdbc
                .sql("select key, value, updated_by, updated_at from trust.rules")
                .query((rs, _) -> stored(rs))
                .list()
                .stream()
                .collect(Collectors.toMap(Stored::key, Function.identity()));
    }

    @Override
    public Optional<Stored> find(String key) {
        return jdbc.sql("select key, value, updated_by, updated_at from trust.rules where key = :k")
                .param("k", key)
                .query((rs, _) -> stored(rs))
                .optional();
    }

    @Override
    public void save(String key, Map<String, Object> value, String staffId, String role, Instant at) {
        jdbc.sql("""
                        insert into trust.rules (key, value, updated_by, role, updated_at)
                        values (:k, cast(:v as jsonb), :by, :role, :at)
                        on conflict (key) do update set value = excluded.value, updated_by = excluded.updated_by,
                               role = excluded.role, updated_at = excluded.updated_at
                        """)
                .param("k", key)
                .param("v", JSON.writeValueAsString(value))
                .param("by", staffId)
                .param("role", role)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .update();
    }

    @Override
    public long[] belowRating(double rating, Instant since, int minReviews, MerchantScope scope) {
        return jdbc.sql("""
                        select count(*) filter (where avg_rating < :rating) as affected, count(*) as total
                          from (select target_id, avg(rating) as avg_rating from trust.reviews
                                 where target_type = 'merchant' and created_at >= :since
                                   and (:everyone or target_id = any(:merchants))
                                 group by target_id having count(*) >= :min) r
                        """)
                .param("rating", rating)
                .param("since", since.atOffset(ZoneOffset.UTC))
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .param("min", minReviews)
                .query((rs, _) -> new long[] {rs.getLong("affected"), rs.getLong("total")})
                .single();
    }

    @Override
    public java.util.List<ca.northline.trust.api.TrustConsequences.Below> averages(Instant since, int minReviews) {
        return jdbc.sql("""
                        select target_id, avg(rating)::float8 as avg_rating, count(*) as n from trust.reviews
                         where target_type = 'merchant' and created_at >= :since
                         group by target_id having count(*) >= :min
                         order by target_id""")
                .param("since", since.atOffset(ZoneOffset.UTC))
                .param("min", minReviews)
                .query((rs, _) -> new ca.northline.trust.api.TrustConsequences.Below(
                        rs.getString("target_id"), rs.getDouble("avg_rating"), rs.getLong("n")))
                .list();
    }

    @Override
    public java.util.List<String> warnedAgain(Instant since) {
        return jdbc.sql("""
                        with flagged as (
                          select coalesce(case when f.target_type = 'merchant' then f.target_id end, f.merchant_id) as m,
                                 f.state, f.action, f.created_at, f.decided_at
                            from trust.flags f where f.rule = 'off_platform_payment')
                        select distinct o.m from flagged o
                         where o.m is not null and coalesce(o.state, 'open') = 'open'
                           and exists (select 1 from flagged w
                                        where w.m = o.m and w.action = 'warn' and w.decided_at >= :since
                                          and o.created_at > w.decided_at)
                         order by o.m""")
                .param("since", since.atOffset(ZoneOffset.UTC))
                .query((rs, _) -> java.util.Objects.requireNonNull(rs.getString(1)))
                .list();
    }

    private static Stored stored(ResultSet rs) throws SQLException {
        return new Stored(
                rs.getString("key"),
                JSON.readValue(rs.getString("value"), MAP),
                rs.getString("updated_by"),
                rs.getObject("updated_at", java.time.OffsetDateTime.class).toInstant());
    }
}
