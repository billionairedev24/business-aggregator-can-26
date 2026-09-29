package ca.northline.food.persistence;

import ca.northline.food.application.KitchenTicketStore;
import ca.northline.food.domain.KitchenStage;
import ca.northline.food.domain.KitchenTicket;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;

/**
 * Kitchen tickets ({@code food.kitchen_tickets}, read-write) over the food orders of the kitchen. {@code orders.*},
 * {@code fulfilment.*} are read-only here: the orders module owns the order and moves its state on the kitchen events
 * (docs/DECISIONS.md › Kitchen).
 */
@Repository
@RequiredArgsConstructor
class KitchenTicketJdbc implements KitchenTicketStore {

    /** Scheduled orders show up this long before they are due. */
    static final Duration SCHEDULED_LEAD = Duration.ofMinutes(60);

    private final JdbcClient jdbc;

    private record Head(
            String orderId,
            @Nullable String ref,
            @Nullable String customerId,
            int groupSize,
            Instant placedAt,
            @Nullable Instant scheduledFor,
            String mode,
            @Nullable Instant customerEta,
            KitchenStage stage,
            @Nullable Instant readyBy,
            boolean courierAssigned,
            @Nullable String courierUserId,
            @Nullable Instant courierEta,
            @Nullable Instant courierArrivedAt) {}

    @Override
    public List<LiveOrderRow> open(String merchantId, Instant now) {
        var heads = jdbc.sql("""
                        select o.id, o.ref, coalesce(g.host_user_id, o.customer_id) as customer_id,
                               case when g.id is null then 0
                                    else 1 + coalesce(cardinality(array_remove(g.member_user_ids, g.host_user_id)), 0)
                               end as group_size,
                               o.placed_at, o.scheduled_for, coalesce(o.fulfilment_mode, 'delivery') as mode, o.customer_eta,
                               coalesce(t.stage, 'new') as stage, t.ready_by,
                               c.run_courier is not null as courier_assigned, c.courier_user_id, c.eta, c.arrived_at
                          from orders.orders o
                          left join food.kitchen_tickets t on t.order_id = o.id and t.merchant_id = :m
                          left join orders.group_orders g on g.id = o.group_order_id
                          left join lateral (
                                select r.courier_id as run_courier, cr.user_id as courier_user_id, st.eta, st.arrived_at
                                  from fulfilment.stops st
                                  join fulfilment.runs r on r.id = st.run_id
                                  left join fulfilment.couriers cr on cr.id = r.courier_id
                                 where st.order_id = o.id and st.kind = 'pickup'
                                 order by st.seq nulls last limit 1) c on true
                         where o.type = 'food'
                           and o.state in ('placed', 'accepted', 'packing', 'ready')
                           and exists (select 1 from orders.order_lines l where l.order_id = o.id and l.merchant_id = :m)
                           and coalesce(t.stage, 'new') <> 'handed_off'
                           and (o.scheduled_for is null or o.scheduled_for <= :due)
                         order by o.placed_at, o.id
                        """)
                .param("m", merchantId)
                .param("due", JdbcTimes.ts(now.plus(SCHEDULED_LEAD)))
                .query((rs, _) -> head(rs))
                .list();
        if (heads.isEmpty()) {
            return List.of();
        }
        var lines = lines(merchantId, heads.stream().map(Head::orderId).toList());
        return heads.stream()
                .map(h -> new LiveOrderRow(
                        h.orderId(),
                        h.ref(),
                        h.customerId(),
                        h.groupSize(),
                        h.placedAt(),
                        h.scheduledFor(),
                        h.mode(),
                        h.customerEta(),
                        h.stage(),
                        h.readyBy(),
                        lines.getOrDefault(h.orderId(), List.of()),
                        h.courierAssigned(),
                        h.courierUserId(),
                        h.courierEta(),
                        h.courierArrivedAt()))
                .toList();
    }

    @Override
    public Optional<KitchenTicket> ticket(String merchantId, String orderId) {
        return jdbc.sql("""
                        select o.id, o.state, coalesce(o.fulfilment_mode, 'delivery') as mode,
                               coalesce(t.stage, 'new') as stage, t.prep_min, t.accepted_at, t.accepted_by, t.ready_by,
                               t.ready_at, t.handed_off_at, t.handed_off_by
                          from orders.orders o
                          left join food.kitchen_tickets t on t.order_id = o.id and t.merchant_id = :m
                         where o.id = :id and o.type = 'food'
                           and exists (select 1 from orders.order_lines l where l.order_id = o.id and l.merchant_id = :m)
                        """)
                .param("m", merchantId)
                .param("id", orderId)
                .query((rs, _) -> KitchenTicket.builder()
                        .orderId(rs.getString("id"))
                        .merchantId(merchantId)
                        .fulfilmentMode(rs.getString("mode"))
                        .orderOpen(!List.of("cancelled", "refunded").contains(rs.getString("state")))
                        .stage(CodedEnum.fromCode(KitchenStage.class, rs.getString("stage")))
                        .prepMin(KitchenSql.intOrNull(rs, "prep_min"))
                        .acceptedAt(JdbcTimes.instant(rs, "accepted_at"))
                        .acceptedBy(rs.getString("accepted_by"))
                        .readyBy(JdbcTimes.instant(rs, "ready_by"))
                        .readyAt(JdbcTimes.instant(rs, "ready_at"))
                        .handedOffAt(JdbcTimes.instant(rs, "handed_off_at"))
                        .handedOffBy(rs.getString("handed_off_by"))
                        .build())
                .optional();
    }

    @Override
    public OrderLoad load(String merchantId, String orderId) {
        return jdbc.sql("""
                        select coalesce(sum(coalesce(l.qty, 1) * coalesce(l.unit_cents, 0)), 0) as cents,
                               coalesce(max(i.prep_add_min), 0) as slowest
                          from orders.order_lines l
                          left join food.menu_items i on i.id = l.menu_item_id
                         where l.order_id = :id and l.merchant_id = :m
                        """)
                .param("id", orderId)
                .param("m", merchantId)
                .query((rs, _) -> new OrderLoad(rs.getLong("cents"), rs.getInt("slowest")))
                .single();
    }

    @Override
    public void save(KitchenTicket t) {
        var p = new HashMap<String, @Nullable Object>();
        p.put("id", t.getOrderId());
        p.put("m", t.getMerchantId());
        p.put("stage", t.getStage().code());
        p.put("prep", t.getPrepMin());
        p.put("acceptedAt", JdbcTimes.ts(t.getAcceptedAt()));
        p.put("acceptedBy", t.getAcceptedBy());
        p.put("readyBy", JdbcTimes.ts(t.getReadyBy()));
        p.put("readyAt", JdbcTimes.ts(t.getReadyAt()));
        p.put("handedOffAt", JdbcTimes.ts(t.getHandedOffAt()));
        p.put("handedOffBy", t.getHandedOffBy());
        jdbc.sql("""
                        insert into food.kitchen_tickets (order_id, merchant_id, stage, prep_min, accepted_at, accepted_by,
                               ready_by, ready_at, handed_off_at, handed_off_by, updated_at)
                        values (:id, :m, :stage, :prep, :acceptedAt, :acceptedBy, :readyBy, :readyAt, :handedOffAt,
                               :handedOffBy, now())
                        on conflict (order_id) do update set stage = excluded.stage, prep_min = excluded.prep_min,
                               accepted_at = excluded.accepted_at, accepted_by = excluded.accepted_by,
                               ready_by = excluded.ready_by, ready_at = excluded.ready_at,
                               handed_off_at = excluded.handed_off_at, handed_off_by = excluded.handed_off_by,
                               updated_at = now()
                        """).params(p).update();
    }

    private Map<String, List<LineRow>> lines(String merchantId, List<String> orderIds) {
        var out = new LinkedHashMap<String, List<LineRow>>();
        jdbc.sql("""
                        select l.order_id, coalesce(l.qty, 1) as qty,
                               coalesce(l.title, i.name, i.name_i18n ->> 'en', 'Item') as title, l.modifiers
                          from orders.order_lines l left join food.menu_items i on i.id = l.menu_item_id
                         where l.merchant_id = :m and l.order_id in (:ids)
                         order by l.order_id, l.id
                        """)
                .param("m", merchantId)
                .param("ids", orderIds)
                .query((rs, _) -> out.computeIfAbsent(rs.getString("order_id"), _ -> new ArrayList<>())
                        .add(new LineRow(
                                rs.getInt("qty"), rs.getString("title"), modifiers(rs.getString("modifiers")))))
                .list();
        return out;
    }

    /** {@code order_lines.modifiers}: {@code ["Large", …]} or {@code [{"name": "Large", …}, …]}. */
    static List<String> modifiers(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonNode node = KitchenSql.JSON.readTree(json);
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

    private static Head head(ResultSet rs) throws SQLException {
        return new Head(
                rs.getString("id"),
                rs.getString("ref"),
                rs.getString("customer_id"),
                rs.getInt("group_size"),
                Objects.requireNonNull(JdbcTimes.instant(rs, "placed_at")),
                JdbcTimes.instant(rs, "scheduled_for"),
                rs.getString("mode"),
                JdbcTimes.instant(rs, "customer_eta"),
                CodedEnum.fromCode(KitchenStage.class, rs.getString("stage")),
                JdbcTimes.instant(rs, "ready_by"),
                rs.getBoolean("courier_assigned"),
                rs.getString("courier_user_id"),
                JdbcTimes.instant(rs, "eta"),
                JdbcTimes.instant(rs, "arrived_at"));
    }
}
