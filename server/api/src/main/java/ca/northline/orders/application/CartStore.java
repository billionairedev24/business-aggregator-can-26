package ca.northline.orders.application;

import ca.northline.orders.application.CartUseCases.CartOwner;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: {@code orders.carts} + {@code orders.cart_items}. */
public interface CartStore {

    Optional<String> cartOf(CartOwner owner);

    /** The owner's cart, created when missing. */
    String ensure(CartOwner owner, Instant now);

    List<Item> items(String cartId);

    /** Adds {@code qty} to the line (created when missing), capped at 99. */
    void add(String cartId, String offerId, @Nullable String variantId, int qty, Instant now);

    boolean setQty(String cartId, String itemId, int qty, Instant now);

    boolean remove(String cartId, String itemId, Instant now);

    /** Moves a guest's lines into the person's cart (quantities added, capped at 99) and deletes the guest's cart. */
    void merge(String guestKey, String userId, Instant now);

    /** Removes the lines a placed order bought. */
    void removeLines(String cartId, List<Item> bought, Instant now);

    record Item(String id, String offerId, @Nullable String variantId, int qty, Instant addedAt) {}
}
