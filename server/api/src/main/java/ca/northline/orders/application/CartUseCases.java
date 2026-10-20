package ca.northline.orders.application;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The consumer cart (S-51): one per signed-in person, else one per browsing session (the consumer-bff's guest id —
 * a key, never an identity). A signed-in call that still carries a guest id merges that guest's cart into the
 * person's first.
 */
public final class CartUseCases {
    private CartUseCases() {}

    /**
     * Whose cart: {@code userId} when signed in, {@code guestKey} = SHA-256 of the guest id when there is one. Both
     * null → nobody (an empty cart; changes are refused).
     */
    public record CartOwner(
            @Nullable String userId, @Nullable String guestKey) {}

    public interface ViewCart {
        CartView view(CartOwner owner, String lang);
    }

    public interface ChangeCart {
        CartView add(CartOwner owner, String offerId, @Nullable String variantId, int qty, String lang);

        /** {@code qty} 0 removes the item. */
        CartView change(CartOwner owner, String itemId, int qty, String lang);

        CartView remove(CartOwner owner, String itemId, String lang);
    }

    /**
     * @param itemCount units in the cart (the header's badge)
     * @param subtotalCents the available items
     * @param plusSavingsCents S-152: what Northline Plus member prices take off the available items (0 for others)
     */
    public record CartView(
            int itemCount, int shopCount, long subtotalCents, List<ShopGroup> groups, long plusSavingsCents) {
        public CartView {
            groups = List.copyOf(groups);
        }

        public static CartView empty() {
            return new CartView(0, 0, 0, List.of(), 0);
        }
    }

    public record ShopGroup(String merchantId, String shopName, List<CartLine> items) {
        public ShopGroup {
            items = List.copyOf(items);
        }
    }

    /**
     * @param available still sellable by an active shop, in stock for {@code qty}
     * @param handlingDays 0 same day, 1 next day, 2; null when it doesn't go on pooled runs
     * @param unitCents what this buyer pays a unit (the member price for a Plus member, S-152)
     * @param listUnitCents the shop's price
     * @param memberUnitCents the shop's Northline Plus member price, null when it offers none
     * @param memberPrice the member price applies to this buyer
     */
    public record CartLine(
            String itemId,
            String merchantId,
            String offerId,
            @Nullable String variantId,
            String productId,
            String name,
            @Nullable String option,
            @Nullable String unit,
            @Nullable String imageUrl,
            long unitCents,
            int qty,
            long lineCents,
            int stock,
            boolean available,
            @Nullable Integer handlingDays,
            @Nullable String ageClass,
            long listUnitCents,
            @Nullable Long memberUnitCents,
            boolean memberPrice) {}
}
