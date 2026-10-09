package ca.northline.orders.persistence;

import ca.northline.orders.application.CheckoutStore;
import ca.northline.shared.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** {@link CheckoutStore}: {@code orders.checkouts} (lines as jsonb) and the order a checkout becomes. */
@Repository
@RequiredArgsConstructor
class CheckoutJdbc implements CheckoutStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    @Override
    public String nextRef() {
        return "NL-"
                + jdbc.sql("select nextval('orders.order_ref_seq')")
                        .query(Long.class)
                        .single();
    }

    @Override
    public void insert(Checkout c) {
        jdbc.sql("""
                        insert into orders.checkouts (id, customer_id, state, order_id, ref, market, delivery, window_id,
                               substitution, address_id, province, subtotal_cents, delivery_fee_cents, delivery_tax_cents,
                               tax_cents, total_cents, delivery_payment_intent, lines, created_at, expires_at,
                               discount_cents, points_cents, tip_cents, promo_code)
                        values (:id, :customer, :state, :order, :ref, :market, :delivery, :window, :substitution,
                                :address, :province, :subtotal, :fee, :feeTax, :tax, :total, :deliveryIntent,
                                cast(:lines as jsonb), :created, :expires, :discount, :points, :tip, :promoCode)
                        """)
                .param("promoCode", c.promoCode())
                .param("discount", c.discountCents())
                .param("points", c.pointsCents())
                .param("tip", c.tipCents())
                .param("id", c.id())
                .param("customer", c.customerId())
                .param("state", c.state())
                .param("order", c.orderId())
                .param("ref", c.ref())
                .param("market", c.market())
                .param("delivery", c.kind())
                .param("window", c.windowId())
                .param("substitution", c.substitution())
                .param("address", c.addressId())
                .param("province", c.province())
                .param("subtotal", c.subtotalCents())
                .param("fee", c.deliveryFeeCents())
                .param("feeTax", c.deliveryTaxCents())
                .param("tax", c.taxCents())
                .param("total", c.totalCents())
                .param("deliveryIntent", c.deliveryPaymentIntent())
                .param("lines", JSON.writeValueAsString(c.lines()))
                .param("created", JdbcTimes.ts(c.createdAt()))
                .param("expires", JdbcTimes.ts(c.expiresAt()))
                .update();
    }

    @Override
    public Optional<Checkout> find(String customerId, String checkoutId) {
        return jdbc.sql("select * from orders.checkouts where id = :id and customer_id = :c")
                .param("id", checkoutId)
                .param("c", customerId)
                .query((rs, _) -> checkout(rs))
                .optional();
    }

    @Override
    public List<Checkout> open(String customerId) {
        return jdbc.sql("select * from orders.checkouts where customer_id = :c and state = 'open'")
                .param("c", customerId)
                .query((rs, _) -> checkout(rs))
                .list();
    }

    @Override
    public List<Checkout> expired(Instant now, int limit) {
        return jdbc.sql("""
                        select * from orders.checkouts where state = 'open' and expires_at < :now
                         order by expires_at limit :limit
                        """)
                .param("now", JdbcTimes.ts(now))
                .param("limit", limit)
                .query((rs, _) -> checkout(rs))
                .list();
    }

    @Override
    public boolean abandon(String checkoutId) {
        return jdbc.sql("update orders.checkouts set state = 'abandoned' where id = :id and state = 'open'")
                        .param("id", checkoutId)
                        .update()
                == 1;
    }

    @Override
    public boolean placed(String checkoutId, Instant at) {
        return jdbc.sql(
                                "update orders.checkouts set state = 'placed', placed_at = :at where id = :id and state = 'open'")
                        .param("id", checkoutId)
                        .param("at", JdbcTimes.ts(at))
                        .update()
                == 1;
    }

    @Override
    public void createOrder(
            Checkout c,
            @Nullable String deliveryArea,
            @Nullable Instant scheduledFor,
            @Nullable Integer idCheckAge,
            Instant at) {
        jdbc.sql("""
                        insert into orders.orders (id, ref, customer_id, type, state, address_id, window_id, scheduled_for,
                               substitution_policy, subtotal_cents, delivery_fee_cents, service_fee_cents, tax_cents,
                               tip_cents, payment_intent_id, placed_at, delivery_area, fulfilment_mode, delivery_kind,
                               checkout_id, id_check_age, discount_cents, points_cents, promo_code)
                        values (:id, :ref, :customer, 'goods', 'placed', :address, :window, :scheduled, :substitution,
                                :subtotal, :fee, 0, :tax, :tip, :intent, :at, :area, 'delivery', :kind, :checkout, :age,
                                :discount, :points, :promo)
                        """)
                .param("tip", c.tipCents())
                .param("discount", c.discountCents())
                .param("points", c.pointsCents())
                .param("promo", c.promoCode())
                .param("age", idCheckAge)
                .param("id", c.orderId())
                .param("ref", c.ref())
                .param("customer", c.customerId())
                .param("address", c.addressId())
                .param("window", c.windowId())
                .param("scheduled", JdbcTimes.ts(scheduledFor))
                .param("substitution", c.substitution())
                .param("subtotal", c.subtotalCents())
                .param("fee", c.deliveryFeeCents())
                .param("tax", c.taxCents())
                .param("intent", c.deliveryPaymentIntent())
                .param("at", JdbcTimes.ts(at))
                .param("area", deliveryArea)
                .param("kind", c.kind())
                .param("checkout", c.id())
                .update();
        for (var l : c.lines()) {
            jdbc.sql("""
                            insert into orders.order_lines (id, order_id, merchant_id, offer_id, variant_id, qty, unit_cents,
                                   state, title, age_class)
                            values (:id, :order, :merchant, :offer, :variant, :qty, :unit, 'pending', :title, :age)
                            """)
                    .param("age", l.ageClass())
                    .param("id", l.lineId())
                    .param("order", c.orderId())
                    .param("merchant", l.merchantId())
                    .param("offer", l.offerId())
                    .param("variant", l.variantId())
                    .param("qty", l.qty())
                    .param("unit", l.unitCents())
                    .param("title", l.option() == null ? l.name() : l.name() + " · " + l.option())
                    .update();
        }
    }

    private static Checkout checkout(ResultSet rs) throws SQLException {
        List<Line> lines = JSON.readValue(
                rs.getString("lines"), JSON.getTypeFactory().constructCollectionType(List.class, Line.class));
        return new Checkout(
                rs.getString("id"),
                rs.getString("customer_id"),
                rs.getString("state"),
                rs.getString("order_id"),
                rs.getString("ref"),
                rs.getString("market"),
                rs.getString("delivery"),
                rs.getString("window_id"),
                rs.getString("substitution"),
                rs.getString("address_id"),
                rs.getString("province").strip(),
                rs.getLong("subtotal_cents"),
                rs.getLong("delivery_fee_cents"),
                rs.getLong("delivery_tax_cents"),
                rs.getLong("tax_cents"),
                rs.getLong("total_cents"),
                rs.getString("delivery_payment_intent"),
                lines,
                JdbcTimes.requiredInstant(rs, "created_at"),
                JdbcTimes.requiredInstant(rs, "expires_at"),
                rs.getLong("discount_cents"),
                rs.getLong("points_cents"),
                rs.getLong("tip_cents"),
                rs.getString("promo_code"));
    }
}
