package ca.northline.orders.persistence;

import ca.northline.orders.application.GroupOrderStore;
import ca.northline.shared.JdbcTimes;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link GroupOrderStore} on {@code orders.group_orders} (V009 + V472) and its items and shares (V472). */
@Repository
@RequiredArgsConstructor
class GroupOrderJdbc implements GroupOrderStore {

    private static final String GROUP = """
            select id, link_code, host_user_id, merchant_id, kitchen_name, kitchen_slug, group_max, member_user_ids,
                   state, payment, fulfilment_mode, scheduled_for, delivery::text as delivery_text, tip_kind, tip_value,
                   order_id, order_ref, created_at, locks_at, placed_at, closed_at
              from orders.group_orders
            """;
    private static final String ACTIVE = "('open', 'locked', 'paying')";

    private final JdbcClient jdbc;

    @Override
    public boolean insert(GroupRow r) {
        return jdbc.sql("""
                            insert into orders.group_orders (id, link_code, host_user_id, merchant_id, kitchen_name,
                                   kitchen_slug, group_max, member_user_ids, state, payment, fulfilment_mode,
                                   scheduled_for, delivery, tip_kind, tip_value, created_at)
                            values (:id, :code, :host, :merchant, :kitchen, :slug, :max, :members, :state, :payment,
                                   :mode, :scheduled, cast(:delivery as jsonb), :tipKind, :tipValue, :created)
                            on conflict (link_code) where link_code is not null do nothing
                            """)
                        .param("id", r.id())
                        .param("code", r.code())
                        .param("host", r.hostId())
                        .param("merchant", r.merchantId())
                        .param("kitchen", r.kitchenName())
                        .param("slug", r.kitchenSlug(), Types.VARCHAR)
                        .param("max", r.groupMax())
                        .param("members", r.members().toArray(String[]::new))
                        .param("state", r.state())
                        .param("payment", r.payment())
                        .param("mode", r.mode())
                        .param("scheduled", JdbcTimes.ts(r.scheduledFor()), Types.TIMESTAMP_WITH_TIMEZONE)
                        .param("delivery", r.delivery(), Types.VARCHAR)
                        .param("tipKind", r.tipKind())
                        .param("tipValue", r.tipValue())
                        .param("created", JdbcTimes.ts(r.createdAt()))
                        .update()
                > 0; // 0: the link code is taken (no exception: the transaction stays usable)
    }

    @Override
    public Optional<GroupRow> find(String id) {
        return jdbc.sql(GROUP + " where id = :id")
                .param("id", id)
                .query((rs, _) -> group(rs))
                .optional();
    }

    @Override
    public Optional<GroupRow> lock(String id) {
        return jdbc.sql(GROUP + " where id = :id for update")
                .param("id", id)
                .query((rs, _) -> group(rs))
                .optional();
    }

    @Override
    public Optional<GroupRow> byCode(String code) {
        return jdbc.sql(GROUP + " where link_code = :code")
                .param("code", code)
                .query((rs, _) -> group(rs))
                .optional();
    }

    @Override
    public List<GroupRow> active(String userId) {
        return jdbc.sql(GROUP + " where :u = any(member_user_ids) and state in " + ACTIVE + " order by created_at desc")
                .param("u", userId)
                .query((rs, _) -> group(rs))
                .list();
    }

    @Override
    public List<String> staleSince(Instant before) {
        return jdbc.sql("select id from orders.group_orders where state in " + ACTIVE + " and created_at < :before")
                .param("before", JdbcTimes.ts(before))
                .query((rs, _) -> rs.getString(1))
                .list();
    }

    @Override
    public void update(GroupRow r) {
        jdbc.sql("""
                        update orders.group_orders
                           set state = :state, tip_kind = :tipKind, tip_value = :tipValue, order_id = :order,
                               order_ref = :ref, locks_at = :locks, placed_at = :placed, closed_at = :closed
                         where id = :id
                        """)
                .param("state", r.state())
                .param("tipKind", r.tipKind())
                .param("tipValue", r.tipValue())
                .param("order", r.orderId(), Types.VARCHAR)
                .param("ref", r.orderRef(), Types.VARCHAR)
                .param("locks", JdbcTimes.ts(r.locksAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("placed", JdbcTimes.ts(r.placedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("closed", JdbcTimes.ts(r.closedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", r.id())
                .update();
    }

    @Override
    public void addMember(String groupId, String userId) {
        jdbc.sql("""
                        update orders.group_orders set member_user_ids = array_append(coalesce(member_user_ids, '{}'), :u)
                         where id = :id and not (:u = any(coalesce(member_user_ids, '{}')))
                        """).param("u", userId).param("id", groupId).update();
    }

    @Override
    public void removeMember(String groupId, String userId) {
        jdbc.sql("update orders.group_orders set member_user_ids = array_remove(member_user_ids, :u) where id = :id")
                .param("u", userId)
                .param("id", groupId)
                .update();
    }

    @Override
    public void linkOrder(String groupId, String orderId) {
        jdbc.sql("update orders.orders set group_order_id = :g where id = :o")
                .param("g", groupId)
                .param("o", orderId)
                .update();
    }

    @Override
    public void insertItem(ItemRow r) {
        jdbc.sql("""
                        insert into orders.group_order_items (id, group_order_id, user_id, menu_item_id, combo_id, qty,
                               option_ids, combo_item_ids, note, title, unit_cents, total_cents, choices, age_class,
                               added_at)
                        values (:id, :group, :user, :item, :combo, :qty, :options, :comboItems, :note, :title, :unit,
                               :total, cast(:choices as jsonb), :age, :at)
                        """)
                .param("id", r.id())
                .param("group", r.groupId())
                .param("user", r.userId())
                .param("item", r.menuItemId(), Types.VARCHAR)
                .param("combo", r.comboId(), Types.VARCHAR)
                .param("qty", r.qty())
                .param("options", r.optionIds().toArray(String[]::new))
                .param("comboItems", r.comboItemIds().toArray(String[]::new))
                .param("note", r.note(), Types.VARCHAR)
                .param("title", r.title())
                .param("unit", r.unitCents())
                .param("total", r.totalCents())
                .param("choices", r.choices())
                .param("age", r.ageClass(), Types.VARCHAR)
                .param("at", JdbcTimes.ts(r.addedAt()))
                .update();
    }

    @Override
    public List<ItemRow> items(String groupId) {
        return jdbc.sql("""
                        select *, choices::text as choices_text from orders.group_order_items
                         where group_order_id = :g order by added_at, id
                        """)
                .param("g", groupId)
                .query((rs, _) -> new ItemRow(
                        rs.getString("id"),
                        rs.getString("group_order_id"),
                        rs.getString("user_id"),
                        rs.getString("menu_item_id"),
                        rs.getString("combo_id"),
                        rs.getInt("qty"),
                        strings(rs.getArray("option_ids")),
                        strings(rs.getArray("combo_item_ids")),
                        rs.getString("note"),
                        rs.getString("title"),
                        rs.getLong("unit_cents"),
                        rs.getLong("total_cents"),
                        rs.getString("choices_text"),
                        rs.getString("age_class"),
                        JdbcTimes.requiredInstant(rs, "added_at")))
                .list();
    }

    @Override
    public boolean deleteItem(String groupId, String itemId) {
        return jdbc.sql("delete from orders.group_order_items where group_order_id = :g and id = :id")
                        .param("g", groupId)
                        .param("id", itemId)
                        .update()
                > 0;
    }

    @Override
    public void deleteItemsOf(String groupId, String userId) {
        jdbc.sql("delete from orders.group_order_items where group_order_id = :g and user_id = :u")
                .param("g", groupId)
                .param("u", userId)
                .update();
    }

    @Override
    public void insertShare(ShareRow r) {
        jdbc.sql("""
                        insert into orders.group_order_shares (id, group_order_id, user_id, paid_by, state,
                               subtotal_cents, delivery_fee_cents, service_fee_cents, fee_tax_cents, tax_cents,
                               tip_cents, total_cents, tax_calculation_id, payment_intent, authorized_at)
                        values (:id, :group, :user, :paidBy, :state, :subtotal, :delivery, :service, :feeTax, :tax,
                               :tip, :total, :calc, :pi, :at)
                        """)
                .param("id", r.id())
                .param("group", r.groupId())
                .param("user", r.userId())
                .param("paidBy", r.paidBy(), Types.VARCHAR)
                .param("state", r.state())
                .param("subtotal", r.subtotalCents())
                .param("delivery", r.deliveryFeeCents())
                .param("service", r.serviceFeeCents())
                .param("feeTax", r.feeTaxCents())
                .param("tax", r.taxCents())
                .param("tip", r.tipCents())
                .param("total", r.totalCents())
                .param("calc", r.taxCalculationId(), Types.VARCHAR)
                .param("pi", r.paymentIntent(), Types.VARCHAR)
                .param("at", JdbcTimes.ts(r.authorizedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    @Override
    public List<ShareRow> shares(String groupId) {
        return jdbc.sql("select * from orders.group_order_shares where group_order_id = :g")
                .param("g", groupId)
                .query((rs, _) -> share(rs))
                .list();
    }

    @Override
    public void updateShare(ShareRow r) {
        jdbc.sql("""
                        update orders.group_order_shares
                           set paid_by = :paidBy, state = :state, payment_intent = :pi, authorized_at = :at
                         where id = :id
                        """)
                .param("paidBy", r.paidBy(), Types.VARCHAR)
                .param("state", r.state())
                .param("pi", r.paymentIntent(), Types.VARCHAR)
                .param("at", JdbcTimes.ts(r.authorizedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", r.id())
                .update();
    }

    @Override
    public void deleteShares(String groupId) {
        jdbc.sql("delete from orders.group_order_shares where group_order_id = :g")
                .param("g", groupId)
                .update();
    }

    @Override
    public List<ShareRow> guestSharesOfOrder(String orderId) {
        return jdbc.sql("""
                        select s.* from orders.group_order_shares s
                          join orders.group_orders g on g.id = s.group_order_id
                         where g.order_id = :o and s.id <> :o and s.state = 'authorized'
                        """).param("o", orderId).query((rs, _) -> share(rs)).list();
    }

    private static GroupRow group(ResultSet rs) throws SQLException {
        return new GroupRow(
                rs.getString("id"),
                rs.getString("link_code"),
                rs.getString("host_user_id"),
                rs.getString("merchant_id"),
                Objects.requireNonNullElse(rs.getString("kitchen_name"), ""),
                rs.getString("kitchen_slug"),
                rs.getInt("group_max"),
                strings(rs.getArray("member_user_ids")),
                rs.getString("state"),
                rs.getString("payment"),
                Objects.requireNonNullElse(rs.getString("fulfilment_mode"), "delivery"),
                JdbcTimes.instant(rs, "scheduled_for"),
                rs.getString("delivery_text"),
                Objects.requireNonNullElse(rs.getString("tip_kind"), "none"),
                rs.getLong("tip_value"),
                rs.getString("order_id"),
                rs.getString("order_ref"),
                JdbcTimes.requiredInstant(rs, "created_at"),
                JdbcTimes.instant(rs, "locks_at"),
                JdbcTimes.instant(rs, "placed_at"),
                JdbcTimes.instant(rs, "closed_at"));
    }

    private static ShareRow share(ResultSet rs) throws SQLException {
        return new ShareRow(
                rs.getString("id"),
                rs.getString("group_order_id"),
                rs.getString("user_id"),
                rs.getString("paid_by"),
                rs.getString("state"),
                rs.getLong("subtotal_cents"),
                rs.getLong("delivery_fee_cents"),
                rs.getLong("service_fee_cents"),
                rs.getLong("fee_tax_cents"),
                rs.getLong("tax_cents"),
                rs.getLong("tip_cents"),
                rs.getString("tax_calculation_id"),
                rs.getString("payment_intent"),
                JdbcTimes.instant(rs, "authorized_at"));
    }

    private static List<String> strings(@Nullable Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
    }
}
