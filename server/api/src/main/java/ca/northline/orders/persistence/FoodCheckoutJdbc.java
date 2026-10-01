package ca.northline.orders.persistence;

import ca.northline.orders.application.FoodCheckoutStore;
import ca.northline.shared.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link FoodCheckoutStore} on {@code orders.food_checkouts}, {@code orders.orders} and {@code orders.order_lines}. */
@Repository
@RequiredArgsConstructor
class FoodCheckoutJdbc implements FoodCheckoutStore {

    private final JdbcClient jdbc;

    @Override
    public String nextRef() {
        return "FD-"
                + jdbc.sql("select nextval('orders.food_order_numbers')")
                        .query(Long.class)
                        .single();
    }

    @Override
    public void insert(CheckoutRow r) {
        var p = new HashMap<String, @Nullable Object>();
        p.put("id", r.id());
        p.put("ref", r.ref());
        p.put("customer", r.customerId());
        p.put("merchant", r.merchantId());
        p.put("kitchen", r.kitchenName());
        p.put("slug", r.kitchenSlug());
        p.put("state", r.state());
        p.put("mode", r.fulfilmentMode());
        p.put("scheduled", JdbcTimes.ts(r.scheduledFor()));
        p.put("eta", JdbcTimes.ts(r.customerEta()));
        p.put("etaFrom", r.etaFromMin());
        p.put("etaTo", r.etaToMin());
        p.put("lines", r.lines());
        p.put("subtotal", r.subtotalCents());
        p.put("delivery_fee", r.deliveryFeeCents());
        p.put("service_fee", r.serviceFeeCents());
        p.put("fee_tax", r.feeTaxCents());
        p.put("tax", r.taxCents());
        p.put("tip", r.tipCents());
        p.put("total", r.totalCents());
        p.put("province", r.province());
        p.put("calc", r.taxCalculationId());
        p.put("pi", r.paymentIntent());
        p.put("delivery", r.delivery());
        p.put("created", JdbcTimes.ts(r.createdAt()));
        jdbc.sql("""
                        insert into orders.food_checkouts (id, ref, customer_id, merchant_id, kitchen_name, kitchen_slug, state,
                               fulfilment_mode, scheduled_for, customer_eta, eta_from_min, eta_to_min, lines, subtotal_cents,
                               delivery_fee_cents, service_fee_cents, fee_tax_cents, tax_cents, tip_cents, total_cents,
                               province, tax_calculation_id, payment_intent, delivery, created_at)
                        values (:id, :ref, :customer, :merchant, :kitchen, :slug, :state, :mode, :scheduled, :eta, :etaFrom,
                               :etaTo, cast(:lines as jsonb), :subtotal, :delivery_fee, :service_fee, :fee_tax, :tax, :tip,
                               :total, :province, :calc, :pi, cast(:delivery as jsonb), :created)
                        """).params(p).update();
    }

    @Override
    public Optional<CheckoutRow> find(String id) {
        return jdbc.sql(
                        "select *, lines::text as lines_text, delivery::text as delivery_text from orders.food_checkouts where id = :id")
                .param("id", id)
                .query((rs, _) -> row(rs))
                .optional();
    }

    @Override
    public void paymentStarted(String id, String paymentIntent) {
        jdbc.sql("update orders.food_checkouts set payment_intent = :pi where id = :id and state = 'pending'")
                .param("pi", paymentIntent)
                .param("id", id)
                .update();
    }

    @Override
    public boolean place(CheckoutRow r, List<OrderLineRow> lines, Instant at) {
        var moved =
                jdbc.sql("""
                        update orders.food_checkouts set state = 'placed', placed_at = :at
                         where id = :id and state = 'pending'
                        """).param("at", JdbcTimes.ts(at)).param("id", r.id()).update() > 0;
        if (!moved) {
            return false;
        }
        jdbc.sql("""
                        insert into orders.orders (id, ref, customer_id, type, state, fulfilment_mode, customer_eta,
                               scheduled_for, subtotal_cents, delivery_fee_cents, service_fee_cents, tax_cents, tip_cents,
                               placed_at, delivery_area)
                        values (:id, :ref, :customer, 'food', 'placed', :mode, :eta, :scheduled, :subtotal, :delivery_fee,
                               :service_fee, :tax, :tip, :at, cast(:delivery as jsonb) ->> 'zone')
                        """)
                .param("id", r.id())
                .param("ref", r.ref())
                .param("customer", r.customerId())
                .param("mode", r.fulfilmentMode())
                .param("eta", JdbcTimes.ts(r.customerEta()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("scheduled", JdbcTimes.ts(r.scheduledFor()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("subtotal", r.subtotalCents())
                .param("delivery_fee", r.deliveryFeeCents())
                .param("service_fee", r.serviceFeeCents())
                .param("tax", r.taxCents() + r.feeTaxCents())
                .param("tip", r.tipCents())
                .param("at", JdbcTimes.ts(at))
                .param("delivery", r.delivery(), Types.VARCHAR)
                .update();
        for (var l : lines) {
            jdbc.sql("""
                            insert into orders.order_lines (id, order_id, merchant_id, menu_item_id, qty, unit_cents, modifiers,
                                   title, state)
                            values (:id, :order, :merchant, :item, :qty, :unit, cast(:modifiers as jsonb), :title, 'pending')
                            """)
                    .param("id", l.id())
                    .param("order", r.id())
                    .param("merchant", r.merchantId())
                    .param("item", l.menuItemId(), Types.VARCHAR)
                    .param("qty", l.qty())
                    .param("unit", l.unitCents())
                    .param("modifiers", l.modifiers())
                    .param("title", l.title())
                    .update();
        }
        return true;
    }

    @Override
    public Optional<OrderState> state(String orderId) {
        return jdbc.sql("select state, delivered_at from orders.orders where id = :id")
                .param("id", orderId)
                .query((rs, _) -> new OrderState(rs.getString("state"), JdbcTimes.instant(rs, "delivered_at")))
                .optional();
    }

    private static CheckoutRow row(ResultSet rs) throws SQLException {
        return new CheckoutRow(
                rs.getString("id"),
                rs.getString("ref"),
                rs.getString("customer_id"),
                rs.getString("merchant_id"),
                rs.getString("kitchen_name"),
                rs.getString("kitchen_slug"),
                rs.getString("state"),
                rs.getString("fulfilment_mode"),
                JdbcTimes.instant(rs, "scheduled_for"),
                JdbcTimes.instant(rs, "customer_eta"),
                rs.getObject("eta_from_min", Integer.class),
                rs.getObject("eta_to_min", Integer.class),
                rs.getString("lines_text"),
                rs.getLong("subtotal_cents"),
                rs.getLong("delivery_fee_cents"),
                rs.getLong("service_fee_cents"),
                rs.getLong("fee_tax_cents"),
                rs.getLong("tax_cents"),
                rs.getLong("tip_cents"),
                rs.getLong("total_cents"),
                rs.getString("province"),
                rs.getString("tax_calculation_id"),
                rs.getString("payment_intent"),
                rs.getString("delivery_text"),
                JdbcTimes.requiredInstant(rs, "created_at"),
                JdbcTimes.instant(rs, "placed_at"));
    }
}
