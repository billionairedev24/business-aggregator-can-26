package ca.northline.payments.persistence;

import ca.northline.payments.application.TipStore;
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

/** {@link TipStore} over {@code payments.courier_tips} (V362). */
@Repository
@RequiredArgsConstructor
class TipJdbc implements TipStore {

    private final JdbcClient jdbc;

    @Override
    public boolean insert(StoredTip t) {
        return jdbc.sql("""
                        insert into payments.courier_tips (id, order_id, customer_id, courier_user_id, amount_cents,
                               source, stripe_payment_intent, state, created_at)
                        values (:id, :order, :customer, :courier, :amount, :source, :pi, :state, :at)
                        on conflict do nothing
                        """)
                        .param("id", t.id())
                        .param("order", t.orderId())
                        .param("customer", t.customerId())
                        .param("courier", t.courierUserId())
                        .param("amount", t.amountCents())
                        .param("source", t.source())
                        .param("pi", t.stripePaymentIntent())
                        .param("state", t.state())
                        .param("at", JdbcTimes.ts(t.createdAt()))
                        .update()
                == 1;
    }

    @Override
    public Optional<StoredTip> find(String id) {
        return jdbc.sql("select * from payments.courier_tips where id = :id")
                .param("id", id)
                .query((rs, _) -> tip(rs))
                .optional();
    }

    @Override
    public Optional<StoredTip> lock(String id) {
        return jdbc.sql("select * from payments.courier_tips where id = :id for update")
                .param("id", id)
                .query((rs, _) -> tip(rs))
                .optional();
    }

    @Override
    public List<StoredTip> ofOrder(String orderId) {
        return jdbc.sql("select * from payments.courier_tips where order_id = :o order by created_at")
                .param("o", orderId)
                .query((rs, _) -> tip(rs))
                .list();
    }

    @Override
    public Optional<StoredTip> checkoutTip(String orderId) {
        return jdbc.sql("select * from payments.courier_tips where order_id = :o and source = 'checkout' for update")
                .param("o", orderId)
                .query((rs, _) -> tip(rs))
                .optional();
    }

    @Override
    public void captured(String id, Instant at) {
        jdbc.sql("""
                        update payments.courier_tips set state = 'captured', captured_at = :at
                         where id = :id and state = 'pending'""")
                .param("id", id)
                .param("at", JdbcTimes.ts(at))
                .update();
    }

    @Override
    public void courier(String id, String courierUserId) {
        jdbc.sql("update payments.courier_tips set courier_user_id = :c where id = :id and courier_user_id is null")
                .param("id", id)
                .param("c", courierUserId)
                .update();
    }

    @Override
    public void allocated(String id, Instant at) {
        jdbc.sql("""
                        update payments.courier_tips set state = 'allocated', allocated_at = :at
                         where id = :id and state = 'captured'""")
                .param("id", id)
                .param("at", JdbcTimes.ts(at))
                .update();
    }

    @Override
    public void canceled(String id) {
        jdbc.sql("update payments.courier_tips set state = 'canceled' where id = :id and state = 'pending'")
                .param("id", id)
                .update();
    }

    @Override
    public void refunded(String id, String reason, String staffId, @Nullable String stripeRefund, Instant at) {
        jdbc.sql("""
                        update payments.courier_tips
                           set state = 'refunded', refunded_at = :at, refund_reason = :reason, refunded_by = :by,
                               stripe_refund = :refund
                         where id = :id""")
                .param("id", id)
                .param("at", JdbcTimes.ts(at))
                .param("reason", reason)
                .param("by", staffId)
                .param("refund", stripeRefund)
                .update();
    }

    private static StoredTip tip(ResultSet rs) throws SQLException {
        return new StoredTip(
                rs.getString("id"),
                rs.getString("order_id"),
                rs.getString("customer_id"),
                rs.getString("courier_user_id"),
                rs.getLong("amount_cents"),
                rs.getString("source"),
                rs.getString("stripe_payment_intent"),
                rs.getString("state"),
                JdbcTimes.requiredInstant(rs, "created_at"),
                JdbcTimes.instant(rs, "captured_at"));
    }
}
