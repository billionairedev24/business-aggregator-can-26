package ca.northline.merchants.persistence;

import ca.northline.merchants.api.MarketplaceMerchants;
import ca.northline.shared.Backlog;
import ca.northline.shared.JdbcTimes;
import ca.northline.shared.MerchantScope;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link MarketplaceMerchants} over {@code merchants.merchants} (S-91). */
@Repository
@RequiredArgsConstructor
class MarketplaceMerchantQueries implements MarketplaceMerchants {

    private static final String IN_SCOPE = "(:everyone or m.id = any(:merchants))";

    private final JdbcClient jdbc;

    @Override
    public List<String> idsIn(@Nullable String province, @Nullable String city) {
        return jdbc.sql("""
                        select m.id from merchants.merchants m
                         where (cast(:province as text) is null or m.province = :province)
                           and (cast(:city as text) is null or lower(m.city) = lower(:city))
                         order by m.id
                        """)
                .param("province", province)
                .param("city", city)
                .query((rs, _) -> rs.getString("id"))
                .list();
    }

    @Override
    public long active(MerchantScope scope) {
        return jdbc.sql("select count(*) from merchants.merchants m where m.status = 'active' and " + IN_SCOPE)
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .query(Long.class)
                .single();
    }

    @Override
    public Backlog applications(MerchantScope scope) {
        return jdbc.sql("""
                        select count(*) as n, min(coalesce(m.submitted_at, m.updated_at)) as oldest
                          from merchants.merchants m where m.status = 'pending' and %s
                        """.formatted(IN_SCOPE))
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .query((rs, _) -> new Backlog(rs.getLong("n"), JdbcTimes.instant(rs, "oldest")))
                .single();
    }
}
