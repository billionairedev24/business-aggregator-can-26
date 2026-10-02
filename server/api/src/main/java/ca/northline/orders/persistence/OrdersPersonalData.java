package ca.northline.orders.persistence;

import static ca.northline.shared.privacy.PersonalDataSql.section;

import ca.northline.shared.privacy.PersonalDataContributor;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * S-105, orders: the person's orders, carts, checkouts and group orders.
 *
 * <p>Erasure: carts and unplaced checkouts go. Placed orders and checkouts are sales records (tax): they stay under
 * the person's id, which identity blanks; a food checkout's delivery address keeps only its city and the postal
 * code's first three characters (the place of supply). An order still on its way holds the erasure until it ends.
 */
@Component
@RequiredArgsConstructor
class OrdersPersonalData implements PersonalDataContributor {

    static final String OPEN = "('placed', 'accepted', 'packing', 'ready', 'picked_up')";

    private final JdbcClient jdbc;

    @Override
    public String module() {
        return "orders";
    }

    @Override
    public List<Section> export(Subject subject) {
        var p = Map.of("u", subject.userId());
        return List.of(
                section(jdbc, "orders.orders", "Orders", "Commandes", """
                        select o.id, o.ref, o.type, o.state, o.fulfilment_mode, o.placed_at, o.delivered_at,
                               o.confirmed_at, o.subtotal_cents, o.delivery_fee_cents, o.service_fee_cents, o.tax_cents,
                               o.tip_cents, o.delivery_area,
                               (select coalesce(json_agg(json_build_object('id', l.id, 'offerId', l.offer_id,
                                       'qty', l.qty, 'unitCents', l.unit_cents)), '[]')
                                  from orders.order_lines l where l.order_id = o.id) as lines
                          from orders.orders o where o.customer_id = :u order by o.placed_at
                        """, p),
                section(jdbc, "orders.checkouts", "Checkouts", "Paiements de commande", """
                        select id, ref, state, market, delivery, province, subtotal_cents, delivery_fee_cents,
                               tax_cents, total_cents, lines, created_at, placed_at
                          from orders.checkouts where customer_id = :u order by created_at
                        """, p),
                section(jdbc, "orders.foodCheckouts", "Food orders", "Commandes de repas", """
                        select id, ref, kitchen_name, state, fulfilment_mode, scheduled_for, lines, total_cents,
                               tip_cents, province, delivery, created_at, placed_at
                          from orders.food_checkouts where customer_id = :u order by created_at
                        """, p),
                section(jdbc, "orders.carts", "Carts", "Paniers", """
                        select c.id, c.created_at, c.updated_at,
                               (select coalesce(json_agg(json_build_object('offerId', i.offer_id, 'variantId',
                                       i.variant_id, 'qty', i.qty)), '[]')
                                  from orders.cart_items i where i.cart_id = c.id) as items
                          from orders.carts c where c.customer_id = :u
                        """, p),
                section(jdbc, "orders.groupOrders", "Group orders", "Commandes de groupe", """
                        select id, merchant_id, host_user_id = :u as host, locks_at
                          from orders.group_orders where host_user_id = :u or :u = any(member_user_ids)
                        """, p));
    }

    @Override
    public Erasure erase(Subject subject) {
        var u = subject.userId();
        var open = jdbc.sql("select count(*) from orders.orders where customer_id = :u and state in " + OPEN)
                .param("u", u)
                .query(Integer.class)
                .single();
        jdbc.sql("delete from orders.carts where customer_id = :u")
                .param("u", u)
                .update();
        jdbc.sql("delete from orders.checkouts where customer_id = :u and state <> 'placed'")
                .param("u", u)
                .update();
        jdbc.sql("delete from orders.food_checkouts where customer_id = :u and state <> 'placed'")
                .param("u", u)
                .update();
        jdbc.sql("""
                        update orders.food_checkouts f
                           set delivery = jsonb_build_object('city', f.delivery->>'city',
                                   'postalCode', left(replace(coalesce(f.delivery->>'postalCode', ''), ' ', ''), 3))
                         where f.customer_id = :u and f.delivery is not null
                           and not exists (select 1 from orders.orders o where o.id = f.id and o.state in
                        """ + OPEN + ")").param("u", u).update();
        jdbc.sql("""
                        update orders.group_orders set member_user_ids = array_remove(member_user_ids, :u)
                         where :u = any(member_user_ids)
                        """).param("u", u).update();
        var outcome = Erasure.done().retaining("orders.orders", Retention.TAX_RECORDS);
        return open > 0 ? outcome.holding("orders.orders", Hold.OPEN_ORDER) : outcome;
    }
}
