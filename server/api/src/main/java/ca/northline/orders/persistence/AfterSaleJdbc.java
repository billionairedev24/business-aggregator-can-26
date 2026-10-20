package ca.northline.orders.persistence;

import ca.northline.orders.application.AfterSaleStore;
import ca.northline.orders.domain.OrderReturn;
import ca.northline.shared.JdbcTimes;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link AfterSaleStore} over {@code orders.*} (S-151, V385). */
@Repository
@RequiredArgsConstructor
class AfterSaleJdbc implements AfterSaleStore {

    private static final String ORDER = """
            select id, ref, coalesce(type, 'goods') as type, coalesce(state, 'placed') as state, customer_id, address_id,
                   delivery_area, delivery_kind, window_id, delivered_at
              from orders.orders where id = :id""";
    private static final String RETURN = """
            select r.id, r.ref, r.order_id, r.merchant_id, r.customer_id, r.reason, r.note, r.photo_key, r.photo_type,
                   r.amount_cents, r.state, r.method, r.restock, r.decline_reason, r.decided_by, r.decided_at,
                   r.collected_at, r.received_at, r.refunded_at, r.dispute_ids, r.created_at,
                   (select array_agg(l.line_id order by l.line_id) from orders.return_lines l where l.return_id = r.id)
                     as line_ids
              from orders.returns r""";

    private final JdbcClient jdbc;

    @Override
    public Optional<OrderFacts> lock(String orderId) {
        return jdbc.sql(ORDER + " for update")
                .param("id", orderId)
                .query(AfterSaleJdbc::order)
                .optional();
    }

    @Override
    public Optional<OrderFacts> find(String orderId) {
        return jdbc.sql(ORDER).param("id", orderId).query(AfterSaleJdbc::order).optional();
    }

    @Override
    public List<LineFacts> lines(String orderId) {
        return jdbc.sql("""
                        select id, merchant_id, offer_id, variant_id, coalesce(title, 'Item') as title,
                               coalesce(qty, 1) as qty, coalesce(unit_cents, 0) as unit_cents,
                               coalesce(state, 'pending') as state, return_id
                          from orders.order_lines where order_id = :id order by id""")
                .param("id", orderId)
                .query((rs, _) -> new LineFacts(
                        rs.getString("id"),
                        Objects.requireNonNullElse(rs.getString("merchant_id"), ""),
                        rs.getString("offer_id"),
                        rs.getString("variant_id"),
                        rs.getString("title"),
                        rs.getInt("qty"),
                        rs.getLong("unit_cents"),
                        rs.getString("state"),
                        rs.getString("return_id")))
                .list();
    }

    @Override
    public void cancelLines(Collection<String> lineIds, boolean restocked, Instant at) {
        if (lineIds.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        update orders.order_lines set state = 'cancelled', cancelled_at = :at, restocked = :restocked
                         where id in (:ids) and coalesce(state, 'pending') not in ('cancelled', 'refunded')""")
                .param("ids", List.copyOf(lineIds))
                .param("restocked", restocked)
                .param("at", JdbcTimes.ts(at), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    @Override
    public void refundLines(Collection<String> lineIds, boolean restocked) {
        if (lineIds.isEmpty()) {
            return;
        }
        jdbc.sql("update orders.order_lines set state = 'refunded', restocked = :restocked where id in (:ids)")
                .param("ids", List.copyOf(lineIds))
                .param("restocked", restocked)
                .update();
    }

    @Override
    public void close(String orderId, String state, Instant at) {
        jdbc.sql("update orders.orders set state = :s, cancelled_at = coalesce(cancelled_at, :at) where id = :id")
                .param("s", state)
                .param("at", JdbcTimes.ts(at), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", orderId)
                .update();
    }

    @Override
    public void state(String orderId, String state) {
        jdbc.sql("update orders.orders set state = :s where id = :id")
                .param("s", state)
                .param("id", orderId)
                .update();
    }

    @Override
    public void readyIfPacked(String orderId) {
        jdbc.sql("""
                        update orders.orders o set state = 'ready'
                         where o.id = :id and o.state in ('placed', 'accepted', 'packing')
                           and exists (select 1 from orders.order_lines l where l.order_id = o.id and l.state = 'packed')
                           and not exists (select 1 from orders.order_lines l
                                            where l.order_id = o.id and coalesce(l.state, 'pending') = 'pending')""").param("id", orderId).update();
    }

    @Override
    public String insertCancellation(Cancellation c) {
        jdbc.sql("""
                        insert into orders.cancellations (id, order_id, merchant_id, actor_id, actor_role, reason, note,
                               restock, line_ids, whole, at)
                        values (:id, :order, :merchant, :actor, :role, :reason, :note, :restock, :lines, :whole, :at)""")
                .param("id", c.id())
                .param("order", c.orderId())
                .param("merchant", c.merchantId(), Types.VARCHAR)
                .param("actor", c.actorId(), Types.VARCHAR)
                .param("role", c.actorRole())
                .param("reason", c.reason())
                .param("note", c.note(), Types.VARCHAR)
                .param("restock", c.restock())
                .param("lines", c.lineIds().toArray(String[]::new))
                .param("whole", c.whole())
                .param("at", JdbcTimes.ts(c.at()), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        return c.id();
    }

    @Override
    public List<Cancellation> cancellations(String orderId) {
        return jdbc.sql("""
                        select id, order_id, merchant_id, actor_id, actor_role, reason, note, restock, line_ids, whole, at
                          from orders.cancellations where order_id = :o order by at, id""")
                .param("o", orderId)
                .query((rs, _) -> new Cancellation(
                        rs.getString("id"),
                        rs.getString("order_id"),
                        rs.getString("merchant_id"),
                        rs.getString("actor_id"),
                        rs.getString("actor_role"),
                        rs.getString("reason"),
                        rs.getString("note"),
                        rs.getBoolean("restock"),
                        ids(rs.getArray("line_ids")),
                        rs.getBoolean("whole"),
                        JdbcTimes.requiredInstant(rs, "at")))
                .list();
    }

    // ── failed delivery ───────────────────────────────────────────────────────────────────────────────────────────

    private static final String FAILURE = """
            select order_id, reason, failed_at, decide_by, attempts, choice, chosen_at, fee_cents, window_id, run_id
              from orders.delivery_failures""";

    @Override
    public Optional<Failure> failure(String orderId) {
        return jdbc.sql(FAILURE + " where order_id = :o")
                .param("o", orderId)
                .query(AfterSaleJdbc::failure)
                .optional();
    }

    @Override
    public void failed(Failure f) {
        jdbc.sql("""
                        insert into orders.delivery_failures (order_id, reason, failed_at, decide_by, attempts, fee_cents,
                               run_id)
                        values (:o, :reason, :at, :by, :attempts, :fee, :run)
                        on conflict (order_id) do update set reason = excluded.reason, failed_at = excluded.failed_at,
                               decide_by = excluded.decide_by, attempts = excluded.attempts,
                               fee_cents = excluded.fee_cents, run_id = excluded.run_id, choice = null,
                               chosen_at = null, window_id = null""")
                .param("o", f.orderId())
                .param("reason", f.reason())
                .param("at", JdbcTimes.ts(f.failedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("by", JdbcTimes.ts(f.decideBy()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("attempts", f.attempts())
                .param("fee", f.feeCents())
                .param("run", f.runId(), Types.VARCHAR)
                .update();
    }

    @Override
    public void chosen(String orderId, String choice, Instant at, @Nullable String windowId) {
        jdbc.sql("""
                        update orders.delivery_failures set choice = :c, chosen_at = :at, window_id = :w
                         where order_id = :o""")
                .param("o", orderId)
                .param("c", choice)
                .param("at", JdbcTimes.ts(at), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("w", windowId, Types.VARCHAR)
                .update();
        if (windowId != null) {
            // the re-delivery's run is the order's run now (tracking shows it)
            jdbc.sql("update orders.orders set window_id = :w where id = :o")
                    .param("o", orderId)
                    .param("w", windowId)
                    .update();
        }
    }

    @Override
    public List<Failure> due(Instant now, int limit) {
        return jdbc.sql(FAILURE + " where choice is null and decide_by <= :now order by decide_by limit :n")
                .param("now", JdbcTimes.ts(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("n", limit)
                .query(AfterSaleJdbc::failure)
                .list();
    }

    // ── returns ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public String nextReturnRef() {
        return "RT-"
                + jdbc.sql("select nextval('orders.return_ref_seq')")
                        .query(Long.class)
                        .single();
    }

    @Override
    public void insert(OrderReturn r) {
        jdbc.sql("""
                        insert into orders.returns (id, ref, order_id, merchant_id, customer_id, reason, note, photo_key,
                               photo_type, amount_cents, state, restock, created_at, updated_at)
                        values (:id, :ref, :order, :merchant, :customer, :reason, :note, :photo, :type, :amount,
                                :state, :restock, :at, :at)""")
                .param("id", r.id())
                .param("ref", r.ref())
                .param("order", r.orderId())
                .param("merchant", r.merchantId())
                .param("customer", r.customerId())
                .param("reason", r.reason())
                .param("note", r.note(), Types.VARCHAR)
                .param("photo", r.photoKey(), Types.VARCHAR)
                .param("type", r.photoType(), Types.VARCHAR)
                .param("amount", r.amountCents())
                .param("state", r.state())
                .param("restock", r.restock())
                .param("at", JdbcTimes.ts(r.createdAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        for (var line : r.lineIds()) {
            jdbc.sql("insert into orders.return_lines (return_id, line_id) values (:r, :l)")
                    .param("r", r.id())
                    .param("l", line)
                    .update();
        }
    }

    @Override
    public void update(OrderReturn r) {
        jdbc.sql("""
                        update orders.returns
                           set state = :state, method = :method, restock = :restock, decline_reason = :decline,
                               decided_by = :by, decided_at = :decided, collected_at = :collected,
                               received_at = :received, refunded_at = :refunded, dispute_ids = :disputes,
                               updated_at = now()
                         where id = :id""")
                .param("id", r.id())
                .param("state", r.state())
                .param("method", r.method(), Types.VARCHAR)
                .param("restock", r.restock())
                .param("decline", r.declineReason(), Types.VARCHAR)
                .param("by", r.decidedBy(), Types.VARCHAR)
                .param("decided", JdbcTimes.ts(r.decidedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("collected", JdbcTimes.ts(r.collectedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("received", JdbcTimes.ts(r.receivedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("refunded", JdbcTimes.ts(r.refundedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("disputes", r.disputeIds().toArray(String[]::new))
                .update();
    }

    @Override
    public Optional<OrderReturn> returnById(String returnId) {
        return jdbc.sql(RETURN + " where r.id = :id")
                .param("id", returnId)
                .query(AfterSaleJdbc::orderReturn)
                .optional();
    }

    @Override
    public Optional<OrderReturn> lockReturn(String returnId) {
        jdbc.sql("select id from orders.returns where id = :id for update")
                .param("id", returnId)
                .query(String.class)
                .optional();
        return returnById(returnId);
    }

    @Override
    public List<OrderReturn> returnsOfOrder(String orderId) {
        return jdbc.sql(RETURN + " where r.order_id = :o order by r.created_at, r.id")
                .param("o", orderId)
                .query(AfterSaleJdbc::orderReturn)
                .list();
    }

    @Override
    public List<OrderReturn> returnsOfMerchant(String merchantId, int limit) {
        return jdbc.sql(RETURN + " where r.merchant_id = :m order by r.created_at desc, r.id desc limit :n")
                .param("m", merchantId)
                .param("n", limit)
                .query(AfterSaleJdbc::orderReturn)
                .list();
    }

    @Override
    public void lineReturn(Collection<String> lineIds, @Nullable String returnId) {
        if (lineIds.isEmpty()) {
            return;
        }
        jdbc.sql("update orders.order_lines set return_id = :r where id in (:ids)")
                .param("r", returnId, Types.VARCHAR)
                .param("ids", List.copyOf(lineIds))
                .update();
    }

    // ── rows ──────────────────────────────────────────────────────────────────────────────────────────────────────

    private static OrderFacts order(ResultSet rs, int row) throws SQLException {
        return new OrderFacts(
                rs.getString("id"),
                rs.getString("ref"),
                rs.getString("type"),
                rs.getString("state"),
                rs.getString("customer_id"),
                rs.getString("address_id"),
                rs.getString("delivery_area"),
                rs.getString("delivery_kind"),
                rs.getString("window_id"),
                JdbcTimes.instant(rs, "delivered_at"));
    }

    private static Failure failure(ResultSet rs, int row) throws SQLException {
        return new Failure(
                rs.getString("order_id"),
                rs.getString("reason"),
                JdbcTimes.requiredInstant(rs, "failed_at"),
                JdbcTimes.requiredInstant(rs, "decide_by"),
                rs.getInt("attempts"),
                rs.getString("choice"),
                JdbcTimes.instant(rs, "chosen_at"),
                rs.getLong("fee_cents"),
                rs.getString("window_id"),
                rs.getString("run_id"));
    }

    private static OrderReturn orderReturn(ResultSet rs, int row) throws SQLException {
        return new OrderReturn(
                rs.getString("id"),
                rs.getString("ref"),
                rs.getString("order_id"),
                rs.getString("merchant_id"),
                rs.getString("customer_id"),
                rs.getString("reason"),
                rs.getString("note"),
                rs.getString("photo_key"),
                rs.getString("photo_type"),
                rs.getLong("amount_cents"),
                rs.getString("state"),
                rs.getString("method"),
                rs.getBoolean("restock"),
                rs.getString("decline_reason"),
                rs.getString("decided_by"),
                JdbcTimes.instant(rs, "decided_at"),
                JdbcTimes.instant(rs, "collected_at"),
                JdbcTimes.instant(rs, "received_at"),
                JdbcTimes.instant(rs, "refunded_at"),
                ids(rs.getArray("dispute_ids")),
                ids(rs.getArray("line_ids")),
                JdbcTimes.requiredInstant(rs, "created_at"));
    }

    private static List<String> ids(@Nullable Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
