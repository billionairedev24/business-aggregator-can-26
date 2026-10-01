package ca.northline.food.persistence;

import ca.northline.food.api.KitchenOrderFeed;
import ca.northline.food.api.KitchenOrderFeed.FoodOrder;
import ca.northline.shared.NavBadgeContributor;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Kitchen sidebar badges: {@code kds} = food orders in the kitchen (New + Cooking) → "6 cooking" / « 6 en cuisine »;
 * {@code menu} = sections of the kitchen's first menu (live menus first) → "3 sections". The orders come from the
 * orders module ({@link KitchenOrderFeed}, S-64); the stages are the kitchen's own tickets.
 */
@Component
@RequiredArgsConstructor
class KitchenNavBadges implements NavBadgeContributor {

    private static final List<String> IN_KITCHEN = List.of("placed", "accepted", "packing");

    private final JdbcClient jdbc;
    private final KitchenOrderFeed orders;
    private final Clock clock;

    @Override
    public Map<String, String> badges(NavBadgeContributor.Context context) {
        var m = context.merchantId();
        var out = new HashMap<String, String>();
        var ids = orders.open(m, clock.instant().plus(Duration.ofMinutes(60))).stream()
                .filter(o -> IN_KITCHEN.contains(o.state()))
                .map(FoodOrder::id)
                .toList();
        int cooking = ids.isEmpty()
                ? 0
                : ids.size()
                        - jdbc.sql("""
                                        select count(*) from food.kitchen_tickets
                                         where merchant_id = :m and order_id in (:ids)
                                           and stage not in ('new', 'cooking')
                                        """)
                                .param("m", m)
                                .param("ids", ids)
                                .query(Integer.class)
                                .single();
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
