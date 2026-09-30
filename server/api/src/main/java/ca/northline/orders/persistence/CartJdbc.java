package ca.northline.orders.persistence;

import ca.northline.orders.application.CartStore;
import ca.northline.orders.application.CartUseCases.CartOwner;
import ca.northline.shared.Ids;
import ca.northline.shared.JdbcTimes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link CartStore}: a person's cart by {@code customer_id}, a guest's by {@code device_key} (the hashed guest id).
 * Guest carts expire 30 days after their last change ({@code expires_at}); a person's never do.
 */
@Repository
@RequiredArgsConstructor
class CartJdbc implements CartStore {

    static final Duration GUEST_CART = Duration.ofDays(30);

    private final JdbcClient jdbc;

    @Override
    public Optional<String> cartOf(CartOwner owner) {
        var userId = owner.userId();
        if (userId != null) {
            return jdbc.sql("select id from orders.carts where customer_id = :u")
                    .param("u", userId)
                    .query(String.class)
                    .optional();
        }
        return jdbc.sql("""
                        select id from orders.carts where customer_id is null and device_key = :k
                           and (expires_at is null or expires_at > now())
                        """).param("k", owner.guestKey()).query(String.class).optional();
    }

    @Override
    public String ensure(CartOwner owner, Instant now) {
        var userId = owner.userId();
        jdbc.sql(userId != null ? """
                          insert into orders.carts (id, customer_id, created_at, updated_at)
                          values (:id, :u, :now, :now) on conflict (customer_id) where customer_id is not null do nothing
                          """ : """
                          insert into orders.carts (id, device_key, expires_at, created_at, updated_at)
                          values (:id, :k, :expires, :now, :now)
                          on conflict (device_key) where customer_id is null and device_key is not null
                          do update set expires_at = excluded.expires_at
                          """)
                .param("id", Ids.next())
                .param("u", userId)
                .param("k", owner.guestKey())
                .param("expires", JdbcTimes.ts(now.plus(GUEST_CART)))
                .param("now", JdbcTimes.ts(now))
                .update();
        return cartOf(owner).orElseThrow();
    }

    @Override
    public List<Item> items(String cartId) {
        return jdbc.sql("select id, offer_id, variant_id, qty, added_at from orders.cart_items where cart_id = :c")
                .param("c", cartId)
                .query((rs, _) -> new Item(
                        rs.getString("id"),
                        rs.getString("offer_id"),
                        rs.getString("variant_id"),
                        rs.getInt("qty"),
                        JdbcTimes.requiredInstant(rs, "added_at")))
                .list();
    }

    @Override
    public void add(String cartId, String offerId, @Nullable String variantId, int qty, Instant now) {
        jdbc.sql("""
                        insert into orders.cart_items (id, cart_id, offer_id, variant_id, qty, added_at)
                        values (:id, :c, :o, :v, :q, :now)
                        on conflict (cart_id, offer_id, coalesce(variant_id, ''))
                        do update set qty = least(99, orders.cart_items.qty + excluded.qty)
                        """)
                .param("id", Ids.next())
                .param("c", cartId)
                .param("o", offerId)
                .param("v", variantId)
                .param("q", qty)
                .param("now", JdbcTimes.ts(now))
                .update();
        touch(cartId, now);
    }

    @Override
    public boolean setQty(String cartId, String itemId, int qty, Instant now) {
        var changed = jdbc.sql("update orders.cart_items set qty = :q where cart_id = :c and id = :id")
                        .param("q", qty)
                        .param("c", cartId)
                        .param("id", itemId)
                        .update()
                == 1;
        touch(cartId, now);
        return changed;
    }

    @Override
    public boolean remove(String cartId, String itemId, Instant now) {
        var removed = jdbc.sql("delete from orders.cart_items where cart_id = :c and id = :id")
                        .param("c", cartId)
                        .param("id", itemId)
                        .update()
                == 1;
        touch(cartId, now);
        return removed;
    }

    @Override
    public void merge(String guestKey, String userId, Instant now) {
        var guest = cartOf(new CartOwner(null, guestKey));
        if (guest.isEmpty()) {
            return;
        }
        var mine = ensure(new CartOwner(userId, null), now);
        jdbc.sql("""
                        insert into orders.cart_items (id, cart_id, offer_id, variant_id, qty, added_at)
                        select 'M' || substr(g.id, 2), :mine, g.offer_id, g.variant_id, g.qty, g.added_at
                          from orders.cart_items g where g.cart_id = :guest
                        on conflict (cart_id, offer_id, coalesce(variant_id, ''))
                        do update set qty = least(99, orders.cart_items.qty + excluded.qty)
                        """).param("mine", mine).param("guest", guest.get()).update();
        jdbc.sql("delete from orders.carts where id = :g")
                .param("g", guest.get())
                .update();
        touch(mine, now);
    }

    @Override
    public void removeLines(String cartId, List<Item> bought, Instant now) {
        for (var line : bought) {
            var where = " where cart_id = :c and offer_id = :o and coalesce(variant_id, '') = coalesce(:v, '')";
            jdbc.sql("delete from orders.cart_items" + where + " and qty <= :q")
                    .param("q", line.qty())
                    .param("c", cartId)
                    .param("o", line.offerId())
                    .param("v", line.variantId())
                    .update();
            jdbc.sql("update orders.cart_items set qty = qty - :q" + where + " and qty > :q")
                    .param("q", line.qty())
                    .param("c", cartId)
                    .param("o", line.offerId())
                    .param("v", line.variantId())
                    .update();
        }
        touch(cartId, now);
    }

    private void touch(String cartId, Instant now) {
        jdbc.sql("""
                        update orders.carts set updated_at = :now,
                               expires_at = case when customer_id is null then :expires else expires_at end
                         where id = :c
                        """)
                .param("now", JdbcTimes.ts(now))
                .param("expires", JdbcTimes.ts(now.plus(GUEST_CART)))
                .param("c", cartId)
                .update();
    }
}
