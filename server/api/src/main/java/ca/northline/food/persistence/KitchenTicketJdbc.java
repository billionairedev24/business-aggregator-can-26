package ca.northline.food.persistence;

import ca.northline.food.api.KitchenOrderFeed;
import ca.northline.food.api.KitchenOrderFeed.FoodOrder;
import ca.northline.food.api.KitchenOrderFeed.Line;
import ca.northline.food.application.KitchenTicketStore;
import ca.northline.food.domain.KitchenStage;
import ca.northline.food.domain.KitchenTicket;
import ca.northline.fulfilment.api.CourierPickups;
import ca.northline.fulfilment.api.CourierPickups.Pickup;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.JdbcTimes;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Kitchen tickets ({@code food.kitchen_tickets}, read-write) over the food orders of the kitchen. The orders come from
 * the orders module ({@link KitchenOrderFeed}) and the courier's pickup from fulfilment ({@link CourierPickups}): food
 * runs SQL on its own schema only (S-64). The orders module owns the order and moves its state on the kitchen events
 * (docs/DECISIONS.md › Kitchen).
 */
@Repository
@RequiredArgsConstructor
class KitchenTicketJdbc implements KitchenTicketStore {

    /** Scheduled orders show up this long before they are due. */
    static final Duration SCHEDULED_LEAD = Duration.ofMinutes(60);

    private static final Pickup NO_PICKUP = new Pickup(false, null, null, null);

    private final JdbcClient jdbc;
    private final KitchenOrderFeed orders;
    private final CourierPickups pickups;

    private record TicketRow(KitchenStage stage, @Nullable Instant readyBy) {}

    @Override
    public List<LiveOrderRow> open(String merchantId, Instant now) {
        var all = orders.open(merchantId, now.plus(SCHEDULED_LEAD));
        if (all.isEmpty()) {
            return List.of();
        }
        var tickets = tickets(merchantId, all.stream().map(FoodOrder::id).toList());
        var live = all.stream()
                .filter(o -> stage(tickets, o.id()) != KitchenStage.HANDED_OFF
                        && stage(tickets, o.id()) != KitchenStage.REFUSED)
                .toList();
        if (live.isEmpty()) {
            return List.of();
        }
        var ids = live.stream().map(FoodOrder::id).toList();
        var lines = lineRows(merchantId, ids);
        var legs = pickups.of(ids);
        return live.stream()
                .map(o -> {
                    var t = tickets.get(o.id());
                    var leg = legs.getOrDefault(o.id(), NO_PICKUP);
                    return new LiveOrderRow(
                            o.id(),
                            o.ref(),
                            o.customerId(),
                            o.groupSize(),
                            o.placedAt(),
                            o.scheduledFor(),
                            o.fulfilmentMode(),
                            o.customerEta(),
                            stage(tickets, o.id()),
                            t == null ? null : t.readyBy(),
                            lines.getOrDefault(o.id(), List.of()),
                            leg.courierAssigned(),
                            leg.courierUserId(),
                            leg.eta(),
                            leg.arrivedAt(),
                            o.idCheckAge());
                })
                .toList();
    }

    @Override
    public Optional<KitchenTicket> ticket(String merchantId, String orderId) {
        return orders.order(merchantId, orderId)
                .map(o -> jdbc.sql("""
                        select stage, prep_min, accepted_at, accepted_by, ready_by, ready_at, handed_off_at,
                               handed_off_by
                          from food.kitchen_tickets where order_id = :id and merchant_id = :m
                        """)
                        .param("m", merchantId)
                        .param("id", orderId)
                        .query((rs, _) -> KitchenTicket.builder()
                                .orderId(o.id())
                                .merchantId(merchantId)
                                .fulfilmentMode(o.fulfilmentMode())
                                .orderOpen(!List.of("cancelled", "refunded").contains(o.state()))
                                .stage(CodedEnum.fromCode(KitchenStage.class, rs.getString("stage")))
                                .prepMin(KitchenSql.intOrNull(rs, "prep_min"))
                                .acceptedAt(JdbcTimes.instant(rs, "accepted_at"))
                                .acceptedBy(rs.getString("accepted_by"))
                                .readyBy(JdbcTimes.instant(rs, "ready_by"))
                                .readyAt(JdbcTimes.instant(rs, "ready_at"))
                                .handedOffAt(JdbcTimes.instant(rs, "handed_off_at"))
                                .handedOffBy(rs.getString("handed_off_by"))
                                .build())
                        .optional()
                        .orElseGet(() -> KitchenTicket.builder()
                                .orderId(o.id())
                                .merchantId(merchantId)
                                .fulfilmentMode(o.fulfilmentMode())
                                .orderOpen(!List.of("cancelled", "refunded").contains(o.state()))
                                .stage(KitchenStage.NEW)
                                .build()));
    }

    @Override
    public OrderLoad load(String merchantId, String orderId) {
        var lines = orders.lines(merchantId, List.of(orderId));
        long cents = lines.stream().mapToLong(l -> l.qty() * l.unitCents()).sum();
        var itemIds =
                lines.stream().map(Line::menuItemId).filter(Objects::nonNull).collect(Collectors.toSet());
        int slowest = itemIds.isEmpty()
                ? 0
                : jdbc.sql("select coalesce(max(prep_add_min), 0) from food.menu_items where id in (:ids)")
                        .param("ids", itemIds)
                        .query(Integer.class)
                        .single();
        return new OrderLoad(cents, slowest);
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

    private Map<String, TicketRow> tickets(String merchantId, Collection<String> orderIds) {
        var out = new HashMap<String, TicketRow>();
        jdbc.sql(
                        "select order_id, stage, ready_by from food.kitchen_tickets where merchant_id = :m and order_id in (:ids)")
                .param("m", merchantId)
                .param("ids", orderIds)
                .query(rs -> {
                    out.put(
                            rs.getString("order_id"),
                            new TicketRow(
                                    CodedEnum.fromCode(KitchenStage.class, rs.getString("stage")),
                                    JdbcTimes.instant(rs, "ready_by")));
                });
        return out;
    }

    private static KitchenStage stage(Map<String, TicketRow> tickets, String orderId) {
        var t = tickets.get(orderId);
        return t == null ? KitchenStage.NEW : t.stage();
    }

    /** The lines by order; a line without a title of its own is named after its menu item. */
    private Map<String, List<LineRow>> lineRows(String merchantId, List<String> orderIds) {
        var lines = orders.lines(merchantId, orderIds);
        var names = itemNames(lines.stream()
                .filter(l -> l.title() == null)
                .map(Line::menuItemId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        return lines.stream()
                .collect(Collectors.groupingBy(
                        Line::orderId,
                        LinkedHashMap::new,
                        Collectors.mapping(
                                l -> new LineRow(l.qty(), title(l, names), l.modifiers()), Collectors.toList())));
    }

    private static String title(Line l, Map<String, String> names) {
        if (l.title() != null) {
            return l.title();
        }
        var id = l.menuItemId();
        return id == null ? "Item" : names.getOrDefault(id, "Item");
    }

    private Map<String, String> itemNames(Set<String> itemIds) {
        var out = new HashMap<String, String>();
        if (itemIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        select id, coalesce(name, name_i18n ->> 'en') as name
                          from food.menu_items where id in (:ids)
                        """).param("ids", itemIds).query(rs -> {
            var name = rs.getString("name");
            if (name != null) {
                out.put(rs.getString("id"), name);
            }
        });
        return out;
    }
}
