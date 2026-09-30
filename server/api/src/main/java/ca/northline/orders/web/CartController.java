package ca.northline.orders.web;

import static ca.northline.orders.web.ConsumerCallers.GUEST_HEADER;

import ca.northline.orders.application.CartUseCases.CartView;
import ca.northline.orders.application.CartUseCases.ChangeCart;
import ca.northline.orders.application.CartUseCases.ViewCart;
import ca.northline.orders.domain.CheckoutMessages;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The consumer cart (S-51; docs/CONSUMER_WEB_PLAN.md § Guest id and cart). Open to guests: the signed-in person's
 * cart, else the guest's ({@code X-Northline-Guest}); a signed-in call with a guest id merges the guest's cart first.
 *
 * <pre>
 * GET    /api/v1/cart                    → { itemCount, shopCount, subtotalCents, groups: [{ merchantId, shopName, items }] }
 * POST   /api/v1/cart/items              { offerId, variantId?, qty } → 201 cart
 * PATCH  /api/v1/cart/items/{itemId}     { qty } (0 removes) → cart
 * DELETE /api/v1/cart/items/{itemId}     → cart
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/cart")
@RequiredArgsConstructor
class CartController {

    private final ViewCart viewCart;
    private final ChangeCart changeCart;
    private final ConsumerCallers callers;

    record AddItemRequest(
            @NotBlank(message = CheckoutMessages.UNAVAILABLE) @Size(max = 40, message = CheckoutMessages.UNAVAILABLE)
                    String offerId,
            @Nullable @Size(max = 40, message = CheckoutMessages.CHOOSE_OPTION) String variantId,
            @NotNull(message = CheckoutMessages.QTY) @Min(value = 1, message = CheckoutMessages.QTY)
                    @Max(value = 99, message = CheckoutMessages.QTY)
                    Integer qty) {}

    record ChangeItemRequest(
            @NotNull(message = CheckoutMessages.QTY) @Min(value = 0, message = CheckoutMessages.QTY)
                    @Max(value = 99, message = CheckoutMessages.QTY)
                    Integer qty) {}

    @GetMapping
    ResponseEntity<CartView> cart(
            @RequestHeader(name = GUEST_HEADER, required = false) @Nullable String guest,
            @RequestParam(required = false) @Nullable String lang,
            Locale locale) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(viewCart.view(callers.owner(guest), ConsumerCallers.lang(lang, locale)));
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    CartView add(
            @RequestHeader(name = GUEST_HEADER, required = false) @Nullable String guest,
            @RequestParam(required = false) @Nullable String lang,
            Locale locale,
            @Valid @RequestBody AddItemRequest body) {
        return changeCart.add(
                callers.owner(guest),
                body.offerId().strip(),
                body.variantId() == null || body.variantId().isBlank() ? null : body.variantId().strip(),
                Objects.requireNonNull(body.qty()),
                ConsumerCallers.lang(lang, locale));
    }

    @PatchMapping("/items/{itemId}")
    CartView change(
            @RequestHeader(name = GUEST_HEADER, required = false) @Nullable String guest,
            @PathVariable String itemId,
            @RequestParam(required = false) @Nullable String lang,
            Locale locale,
            @Valid @RequestBody ChangeItemRequest body) {
        return changeCart.change(
                callers.owner(guest), itemId, Objects.requireNonNull(body.qty()), ConsumerCallers.lang(lang, locale));
    }

    @DeleteMapping("/items/{itemId}")
    CartView remove(
            @RequestHeader(name = GUEST_HEADER, required = false) @Nullable String guest,
            @PathVariable String itemId,
            @RequestParam(required = false) @Nullable String lang,
            Locale locale) {
        return changeCart.remove(callers.owner(guest), itemId, ConsumerCallers.lang(lang, locale));
    }
}
