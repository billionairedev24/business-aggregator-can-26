package ca.northline.orders.persistence;

import ca.northline.food.api.KitchenOrderFeed;
import ca.northline.shared.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link KitchenOrderFeed} (S-64): the kitchen display reads food orders, group orders and order lines through the
 * orders module instead of querying {@code orders.*} itself.
 */
@Repository
@RequiredArgsConstructor
class KitchenOrderFeedJdbc implements KitchenOrderFeed {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ORDER = """
            select o.id, o.ref, coalesce(g.host_user_id, o.customer_id) as customer_id,
                   case when g.id is null then 0
                        else 1 + coalesce(cardinality(array_remove(g.member_user_ids, g.host_user_id)), 0)
                   end as group_size,
                   o.placed_at, o.scheduled_for, o.fulfilment_mode as mode, o.customer_eta, o.state
              from orders.orders o
              left join orders.group_orders g on g.id = o.group_order_id
             where o.type = 'food'
               and exists (select 1 from orders.order_lines l where l.order_id = o.id and l.merchant_id = :m)
            """;

    private final JdbcClient jdbc;

    @Override
    public List<FoodOrder> open(String merchantId, Instant dueBy) {
        return jdbc.sql(ORDER + """
                           and o.state in ('placed', 'accepted', 'packing', 'ready')
                           and (o.scheduled_for is null or o.scheduled_for <= :due)
                         order by o.placed_at, o.id
                        """)
                .param("m", merchantId)
                .param("due", JdbcTimes.ts(dueBy))
                .query((rs, _) -> order(rs))
                .list();
    }

    @Override
    public Optional<FoodOrder> order(String merchantId, String orderId) {
        return jdbc.sql(ORDER + " and o.id = :id")
                .param("m", merchantId)
                .param("id", orderId)
                .query((rs, _) -> order(rs))
                .optional();
    }

    @Override
    public List<Line> lines(String merchantId, Collection<String> orderIds) {
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select id, order_id, coalesce(qty, 1) as qty, coalesce(unit_cents, 0) as unit_cents,
                               menu_item_id, title, modifiers
                          from orders.order_lines
                         where merchant_id = :m and order_id in (:ids)
                         order by order_id, id
                        """)
                .param("m", merchantId)
                .param("ids", orderIds)
                .query((rs, _) -> new Line(
                        rs.getString("id"),
                        rs.getString("order_id"),
                        rs.getInt("qty"),
                        rs.getLong("unit_cents"),
                        rs.getString("menu_item_id"),
                        rs.getString("title"),
                        modifiers(rs.getString("modifiers"))))
                .list();
    }

    private static FoodOrder order(ResultSet rs) throws SQLException {
        return new FoodOrder(
                rs.getString("id"),
                rs.getString("ref"),
                rs.getString("customer_id"),
                rs.getInt("group_size"),
                JdbcTimes.requiredInstant(rs, "placed_at"),
                JdbcTimes.instant(rs, "scheduled_for"),
                rs.getString("mode"),
                JdbcTimes.instant(rs, "customer_eta"),
                rs.getString("state"));
    }

    /** {@code order_lines.modifiers}: {@code ["Large", …]} or {@code [{"name": "Large", …}, …]}. */
    static List<String> modifiers(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonNode node = JSON.readTree(json);
        if (!node.isArray()) {
            return List.of();
        }
        var out = new ArrayList<String>();
        for (var m : node) {
            if (m.isString()) {
                out.add(m.asString());
            } else if (m.has("name")) {
                out.add(m.get("name").asString());
            }
        }
        return out;
    }
}
