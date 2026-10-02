package ca.northline.orders.persistence;

import ca.northline.shared.privacy.PersonalDataContributor.Hold;
import ca.northline.shared.privacy.RetentionContributor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * S-107, orders: {@code orders.sales_records} — "Transaction and tax records: 7 years". Seven years after an order
 * was placed it stops being anyone's: the order keeps its lines, amounts, tax, dates and business (the sales record),
 * and loses the customer, the address and the delivery area; the checkout snapshots (cart lines, delivery address)
 * go. An order still under way is a legal hold for every module ({@link Hold#OPEN_ORDER}); the order lines payments
 * holds are named here by their order.
 */
@Component
@RequiredArgsConstructor
class OrdersRetention implements RetentionContributor {

    static final String SALES_RECORDS = "orders.sales_records";

    private static final String ORDERS = """
            from orders.orders o
             where o.placed_at < :cutoff and o.state not in %s
               and (o.customer_id is not null or o.address_id is not null or o.delivery_area is not null)
               and not ('order:' || o.id = any(:held)) and not (coalesce(o.customer_id, '') = any(:subjects))
            """.formatted(OrdersPersonalData.OPEN);
    private static final String CHECKOUTS = """
            from orders.checkouts c
             where c.created_at < :cutoff and c.state <> 'open'
               and not ('order:' || c.order_id = any(:held)) and not (c.customer_id = any(:subjects))
            """;
    private static final String FOOD_CHECKOUTS = """
            from orders.food_checkouts f
             where f.created_at < :cutoff and f.state <> 'pending'
               and not ('order:' || f.id = any(:held)) and not (f.customer_id = any(:subjects))
            """;

    private final JdbcClient jdbc;

    @Override
    public String module() {
        return "orders";
    }

    @Override
    public Set<String> categories() {
        return Set.of(SALES_RECORDS);
    }

    @Override
    public List<HeldRef> holds(Instant now) {
        return jdbc.sql("select id from orders.orders where state in " + OrdersPersonalData.OPEN)
                .query((rs, _) -> rs.getString(1))
                .list()
                .stream()
                .map(id -> HeldRef.open("order", id, Hold.OPEN_ORDER))
                .toList();
    }

    /** An order line or a food order (payments' escrow references) held → its order held. */
    @Override
    public List<HeldRef> relate(Collection<HeldRef> holds) {
        var related = new ArrayList<HeldRef>();
        var lines = new ArrayList<HeldRef>();
        for (var hold : holds) {
            switch (hold.ref().type()) {
                case "food_order" -> related.add(hold.as("order", hold.ref().id()));
                case "order_line" -> lines.add(hold);
                default -> {}
            }
        }
        if (!lines.isEmpty()) {
            var orderOf = jdbc.sql("select id, order_id from orders.order_lines where id = any(:ids)")
                    .param("ids", lines.stream().map(h -> h.ref().id()).distinct().toArray(String[]::new))
                    .query((rs, _) -> Map.entry(rs.getString("id"), rs.getString("order_id")))
                    .list()
                    .stream()
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, _) -> a));
            for (var hold : lines) {
                var order = orderOf.get(hold.ref().id());
                if (order != null) {
                    related.add(hold.as("order", order));
                }
            }
        }
        return related;
    }

    @Override
    public long expired(Run run) {
        var p = params(run);
        return count(ORDERS, p) + count(CHECKOUTS, p) + count(FOOD_CHECKOUTS, p);
    }

    private long count(String from, Map<String, Object> p) {
        return jdbc.sql("select count(*) " + from).params(p).query(Long.class).single();
    }

    @Override
    public long purge(Run run) {
        var p = params(run);
        var done = jdbc.sql("""
                        update orders.orders set customer_id = null, address_id = null, delivery_area = null
                         where id in (select o.id %s limit :batch)
                        """.formatted(ORDERS))
                .params(p)
                .update();
        done += jdbc.sql("delete from orders.checkouts where id in (select c.id %s limit :batch)".formatted(CHECKOUTS))
                .params(p)
                .update();
        done += jdbc.sql("delete from orders.food_checkouts where id in (select f.id %s limit :batch)"
                        .formatted(FOOD_CHECKOUTS))
                .params(p)
                .update();
        return done;
    }

    private static Map<String, Object> params(Run run) {
        return Map.of(
                "cutoff", run.before(), "held", run.heldKeys(), "subjects", run.subjects(), "batch", run.batch());
    }
}
