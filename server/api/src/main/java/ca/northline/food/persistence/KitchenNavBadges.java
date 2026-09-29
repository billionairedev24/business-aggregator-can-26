package ca.northline.food.persistence;

import ca.northline.shared.NavBadgeContributor;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Kitchen sidebar badges: {@code kds} = food orders in the kitchen (New + Cooking) → "6 cooking" / « 6 en cuisine »;
 * {@code menu} = sections of the kitchen's first menu (live menus first) → "3 sections". One query each.
 */
@Component
@RequiredArgsConstructor
class KitchenNavBadges implements NavBadgeContributor {

    private final JdbcClient jdbc;

    @Override
    public Map<String, String> badges(NavBadgeContributor.Context context) {
        var m = context.merchantId();
        var out = new HashMap<String, String>();
        int cooking = jdbc.sql("""
                        select count(*) from orders.orders o
                          left join food.kitchen_tickets t on t.order_id = o.id and t.merchant_id = :m
                         where o.type = 'food' and o.state in ('placed', 'accepted', 'packing')
                           and coalesce(t.stage, 'new') in ('new', 'cooking')
                           and (o.scheduled_for is null or o.scheduled_for <= now() + interval '60 minutes')
                           and exists (select 1 from orders.order_lines l where l.order_id = o.id and l.merchant_id = :m)
                        """).param("m", m).query(Integer.class).single();
        if (cooking > 0) {
            out.put("kds", (context.french() ? "%d en cuisine" : "%d cooking").formatted(cooking));
        }
        int sections = jdbc.sql("""
                        select count(*) from food.menu_sections s
                         where s.menu_id = (select id from food.menus where merchant_id = :m
                                             order by (status = 'live') desc, sort, id limit 1)
                        """).param("m", m).query(Integer.class).single();
        if (sections > 0) {
            out.put("menu", sections == 1 ? "1 section" : "%d sections".formatted(sections));
        }
        return out;
    }
}
