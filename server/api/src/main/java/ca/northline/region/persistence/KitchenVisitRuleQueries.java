package ca.northline.region.persistence;

import ca.northline.region.api.KitchenVisitRules;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link KitchenVisitRules} over {@code region.regions.kitchen_visit} (V321): market, else province, else optional. */
@Repository
@RequiredArgsConstructor
class KitchenVisitRuleQueries implements KitchenVisitRules {

    private final JdbcClient jdbc;

    @Override
    public boolean required(@Nullable String province, @Nullable String marketId) {
        var rule = jdbc.sql("""
                        select coalesce(m.kitchen_visit, mp.kitchen_visit, p.kitchen_visit, 'optional')
                          from (select 1) one
                          left join region.regions m on m.kind = 'market' and m.id = cast(:market as text)
                          left join region.regions mp on mp.kind = 'province' and mp.id = m.parent_id
                          left join region.regions p on p.kind = 'province' and m.id is null
                                                    and p.province = upper(cast(:province as text))
                        """)
                .param("market", marketId)
                .param("province", province)
                .query(String.class)
                .single();
        return "required".equals(rule);
    }
}
