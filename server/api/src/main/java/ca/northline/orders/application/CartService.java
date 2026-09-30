package ca.northline.orders.application;

import static ca.northline.orders.domain.CheckoutMessages.CHOOSE_OPTION;
import static ca.northline.orders.domain.CheckoutMessages.GUEST;
import static ca.northline.orders.domain.CheckoutMessages.ONLY_LEFT;
import static ca.northline.orders.domain.CheckoutMessages.QTY;
import static ca.northline.orders.domain.CheckoutMessages.SOLD_OUT;
import static ca.northline.orders.domain.CheckoutMessages.UNAVAILABLE;

import ca.northline.merchants.api.ShopDirectory;
import ca.northline.orders.api.SellableOffers;
import ca.northline.orders.api.SellableOffers.Sellable;
import ca.northline.orders.application.CartUseCases.CartLine;
import ca.northline.orders.application.CartUseCases.CartOwner;
import ca.northline.orders.application.CartUseCases.CartView;
import ca.northline.orders.application.CartUseCases.ShopGroup;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CartUseCases}: lines are checked against the catalogue ({@link SellableOffers}) and the shops
 * ({@link ShopDirectory}) every time the cart is read, so a hidden listing, a paused shop or sold-out stock shows at
 * once. Grouped by shop in the order the shops were first added (the design's multi-shop cart).
 */
@Service
@RequiredArgsConstructor
@Transactional
class CartService implements CartUseCases.ViewCart, CartUseCases.ChangeCart {

    static final int MAX_QTY = 99;

    private final CartStore carts;
    private final SellableOffers offers;
    private final ShopDirectory shops;
    private final Clock clock;

    @Override
    public CartView view(CartOwner owner, String lang) {
        return cartIdOf(owner).map(id -> view(id, lang)).orElseGet(CartView::empty);
    }

    @Override
    public CartView add(CartOwner owner, String offerId, @Nullable String variantId, int qty, String lang) {
        requireQty(qty, 1);
        var cartId = ensure(owner);
        var sellable = offers.find(List.of(new SellableOffers.Item(offerId, variantId)), lang);
        if (sellable.isEmpty()
                || shops.shops(List.of(sellable.getFirst().merchantId())).isEmpty()) {
            throw RuleViolation.of("offerId", "available", UNAVAILABLE);
        }
        var line = sellable.stream()
                .filter(s -> Objects.equals(s.variantId(), variantId))
                .findFirst()
                .orElseThrow(() -> RuleViolation.of("variantId", "required", CHOOSE_OPTION));
        if (line.hasVariants() && variantId == null) {
            throw RuleViolation.of("variantId", "required", CHOOSE_OPTION);
        }
        var already = carts.items(cartId).stream()
                .filter(i -> i.offerId().equals(offerId) && Objects.equals(i.variantId(), variantId))
                .mapToInt(CartStore.Item::qty)
                .sum();
        requireStock(line, already + qty);
        requireQty(already + qty, 1);
        carts.add(cartId, offerId, variantId, qty, clock.instant());
        return view(cartId, lang);
    }

    @Override
    public CartView change(CartOwner owner, String itemId, int qty, String lang) {
        requireQty(qty, 0);
        var cartId = cartIdOf(owner).orElseThrow(() -> new NotFound("cart item", itemId));
        var item = carts.items(cartId).stream()
                .filter(i -> i.id().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new NotFound("cart item", itemId));
        if (qty == 0) {
            carts.remove(cartId, itemId, clock.instant());
            return view(cartId, lang);
        }
        if (qty > item.qty()) {
            var line = offers.find(List.of(new SellableOffers.Item(item.offerId(), item.variantId())), lang).stream()
                    .filter(s -> Objects.equals(s.variantId(), item.variantId()))
                    .findFirst()
                    .orElseThrow(() -> RuleViolation.of("qty", "available", UNAVAILABLE));
            requireStock(line, qty);
        }
        carts.setQty(cartId, itemId, qty, clock.instant());
        return view(cartId, lang);
    }

    @Override
    public CartView remove(CartOwner owner, String itemId, String lang) {
        var cartId = cartIdOf(owner).orElseThrow(() -> new NotFound("cart item", itemId));
        if (!carts.remove(cartId, itemId, clock.instant())) {
            throw new NotFound("cart item", itemId);
        }
        return view(cartId, lang);
    }

    /** The cart id after merging a guest's cart into the signed-in person's (when the call carries both). */
    Optional<String> cartIdOf(CartOwner owner) {
        var userId = owner.userId();
        var guestKey = owner.guestKey();
        if (userId != null && guestKey != null) {
            carts.merge(guestKey, userId, clock.instant());
        }
        return userId == null && guestKey == null ? Optional.empty() : carts.cartOf(owner);
    }

    private String ensure(CartOwner owner) {
        if (owner.userId() == null && owner.guestKey() == null) {
            throw RuleViolation.of("X-Northline-Guest", "required", GUEST);
        }
        return cartIdOf(owner).orElseGet(() -> carts.ensure(owner, clock.instant()));
    }

    /** The cart with every line checked, grouped by shop. */
    CartView view(String cartId, String lang) {
        var items = carts.items(cartId);
        if (items.isEmpty()) {
            return CartView.empty();
        }
        var sellable = offers
                .find(
                        items.stream()
                                .map(i -> new SellableOffers.Item(i.offerId(), i.variantId()))
                                .toList(),
                        lang)
                .stream()
                .collect(Collectors.toMap(
                        s -> s.offerId() + "|" + Objects.requireNonNullElse(s.variantId(), ""),
                        Function.identity(),
                        (a, _) -> a));
        var names = shops
                .shops(sellable.values().stream()
                        .map(Sellable::merchantId)
                        .distinct()
                        .toList())
                .stream()
                .collect(Collectors.toMap(ShopDirectory.Shop::merchantId, ShopDirectory.Shop::displayName));
        var groups = new LinkedHashMap<String, List<CartLine>>();
        var unknown = new ArrayList<CartLine>();
        for (var item : items.stream()
                .sorted(Comparator.comparing(CartStore.Item::addedAt))
                .toList()) {
            var s = sellable.get(item.offerId() + "|" + Objects.requireNonNullElse(item.variantId(), ""));
            if (s == null) {
                unknown.add(new CartLine(
                        item.id(),
                        "",
                        item.offerId(),
                        item.variantId(),
                        "",
                        "",
                        null,
                        null,
                        null,
                        0,
                        item.qty(),
                        0,
                        0,
                        false,
                        null));
                continue;
            }
            var open = names.containsKey(s.merchantId());
            var line = new CartLine(
                    item.id(),
                    s.merchantId(),
                    item.offerId(),
                    item.variantId(),
                    s.productId(),
                    s.name(),
                    s.option(),
                    s.unit(),
                    s.imageUrl(),
                    s.unitCents(),
                    item.qty(),
                    s.unitCents() * item.qty(),
                    s.stock(),
                    open && s.stock() >= item.qty(),
                    s.handlingDays());
            groups.computeIfAbsent(s.merchantId(), _ -> new ArrayList<>()).add(line);
        }
        var shopGroups = new ArrayList<ShopGroup>();
        groups.forEach((merchantId, lines) ->
                shopGroups.add(new ShopGroup(merchantId, names.getOrDefault(merchantId, ""), lines)));
        if (!unknown.isEmpty()) {
            shopGroups.add(new ShopGroup("", "", unknown));
        }
        var all = shopGroups.stream().flatMap(g -> g.items().stream()).toList();
        return new CartView(
                all.stream().mapToInt(CartLine::qty).sum(),
                groups.size(),
                all.stream()
                        .filter(CartLine::available)
                        .mapToLong(CartLine::lineCents)
                        .sum(),
                shopGroups);
    }

    private static void requireQty(int qty, int min) {
        if (qty < min || qty > MAX_QTY) {
            throw RuleViolation.of("qty", "range", QTY);
        }
    }

    private static void requireStock(Sellable line, int qty) {
        if (line.stock() <= 0) {
            throw RuleViolation.of("qty", "stock", SOLD_OUT);
        }
        if (qty > line.stock()) {
            throw RuleViolation.of("qty", "stock", ONLY_LEFT.formatted(line.stock()));
        }
    }
}
