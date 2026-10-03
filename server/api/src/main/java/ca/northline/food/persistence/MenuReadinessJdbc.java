package ca.northline.food.persistence;

import ca.northline.food.api.MenuReadiness;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link MenuReadiness} over {@code food.menu_items}, their sections and menus. */
@Repository
@RequiredArgsConstructor
class MenuReadinessJdbc implements MenuReadiness {

    private final JdbcClient jdbc;

    @Override
    public Map<String, Counts> counts(Collection<String> merchantIds) {
        if (merchantIds.isEmpty()) {
            return Map.of();
        }
        return jdbc
                .sql("""
                        select k.merchant_id, count(i.id) as total,
                               count(i.id) filter (where i.status = 'published') as submitted,
                               count(i.id) filter (where i.status = 'published' and i.vetting = 'approved'
                                                     and m.status = 'live') as live,
                               exists (select 1 from food.kitchen_settings ks where ks.merchant_id = k.merchant_id) as set_up
                          from unnest(cast(:m as text[])) as k(merchant_id)
                          left join food.menu_items i on i.merchant_id = k.merchant_id
                          left join food.menu_sections s on s.id = i.section_id
                          left join food.menus m on m.id = s.menu_id
                         group by k.merchant_id
                        having count(i.id) > 0
                            or exists (select 1 from food.kitchen_settings ks where ks.merchant_id = k.merchant_id)
                        """)
                .param("m", merchantIds.toArray(String[]::new))
                .query((rs, _) -> Map.entry(
                        rs.getString("merchant_id"),
                        new Counts(
                                rs.getInt("total"),
                                rs.getInt("submitted"),
                                rs.getInt("live"),
                                rs.getBoolean("set_up"))))
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
