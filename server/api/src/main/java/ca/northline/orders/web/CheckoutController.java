package ca.northline.orders.web;

import ca.northline.orders.application.CheckoutUseCases;
import ca.northline.orders.application.CheckoutUseCases.AddressInput;
import ca.northline.orders.application.CheckoutUseCases.PlaceOrder;
import ca.northline.orders.application.CheckoutUseCases.Quote;
import ca.northline.orders.application.CheckoutUseCases.QuoteCheckout;
import ca.northline.orders.application.CheckoutUseCases.SetUpCheckout;
import ca.northline.orders.application.CheckoutUseCases.Setup;
import ca.northline.orders.application.CheckoutUseCases.StartCheckout;
import ca.northline.orders.application.FoodCheckout;
import ca.northline.payments.api.IdempotentRequests;
import ca.northline.region.api.FallbackMarket;
import ca.northline.shared.security.CurrentUser;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consumer checkout (S-51), signed-in people only (single-factor tokens accepted; paying asks for a step-up):
 *
 * <pre>
 * GET  /api/v1/me/checkout?market=           cart, saved addresses, delivery options, payment provider, step-up need
 * POST /api/v1/me/checkout/quote             { kind, windowId?, address, substitution, promoCode?, usePoints?, tip? }
 *                                            → subtotal, discount, points, delivery, tax, tip, total
 * POST /api/v1/me/checkouts                  same body; Idempotency-Key, X-Step-Up → 201 { checkoutId, orderId, intents }
 * POST /api/v1/me/checkouts/{id}/place       Idempotency-Key → 201 { orderId, ref } (every payment authorized)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
class CheckoutController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String STEP_UP = "X-Step-Up";

    private final SetUpCheckout setUp;
    private final QuoteCheckout quote;
    private final StartCheckout start;
    private final PlaceOrder place;
    private final IdempotentRequests idempotent;
    private final FallbackMarket fallback;

    /**
     * The checkout form: {@code kind} pooled | direct, the address saved or new, the substitution choice; mobile gaps
     * part 2: {@code promoCode}, {@code usePoints} and the courier's {@code tip} ({kind: none | amount | percent, value}).
     */
    record CheckoutRequest(
            @Nullable String kind,
            @Nullable String windowId,
            @Nullable AddressInput address,
            @Nullable String substitution,
            @Nullable String promoCode,
            @Nullable Boolean usePoints,
            FoodCheckout.@Nullable Tip tip) {

        CheckoutUseCases.Request toCommand() {
            return new CheckoutUseCases.Request(
                    Objects.requireNonNullElse(kind, ""),
                    windowId,
                    Objects.requireNonNullElseGet(
                            address, () -> new AddressInput(null, null, null, null, null, null, null)),
                    Objects.requireNonNullElse(substitution, ""),
                    promoCode,
                    Boolean.TRUE.equals(usePoints),
                    Objects.requireNonNullElse(tip, FoodCheckout.Tip.NONE));
        }
    }

    @GetMapping("/checkout")
    ResponseEntity<Setup> setup(
            CurrentUser user,
            @RequestParam(required = false) @Nullable String market,
            @RequestParam(required = false) @Nullable String lang,
            Locale locale) {
        // no market named: the region's fallback market (S-47), never a city chosen here
        var city = market != null && !market.isBlank()
                ? market
                : fallback.fallback().map(FallbackMarket.City::city).orElse("");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(setUp.setup(user.userId(), user.mfa(), city, ConsumerCallers.lang(lang, locale)));
    }

    @PostMapping("/checkout/quote")
    Quote quote(
            CurrentUser user,
            @RequestParam(required = false) @Nullable String lang,
            Locale locale,
            @RequestBody CheckoutRequest body) {
        return quote.quote(user.userId(), body.toCommand(), ConsumerCallers.lang(lang, locale));
    }

    @PostMapping("/checkouts")
    ResponseEntity<String> start(
            CurrentUser user,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) @Nullable String key,
            @RequestHeader(name = STEP_UP, required = false) @Nullable String proof,
            @RequestParam(required = false) @Nullable String lang,
            Locale locale,
            @RequestBody CheckoutRequest body) {
        var outcome = idempotent.run(
                "consumer:" + user.userId() + ":checkout",
                key,
                body,
                HttpStatus.CREATED.value(),
                () -> start.start(
                        user.userId(), user.mfa(), proof, body.toCommand(), key, ConsumerCallers.lang(lang, locale)));
        return json(outcome);
    }

    @PostMapping("/checkouts/{checkoutId}/place")
    ResponseEntity<String> place(
            CurrentUser user,
            @PathVariable String checkoutId,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) @Nullable String key) {
        var outcome = idempotent.run(
                "consumer:" + user.userId() + ":place:" + checkoutId,
                key,
                null,
                HttpStatus.CREATED.value(),
                () -> place.place(user.userId(), checkoutId));
        return json(outcome);
    }

    private static ResponseEntity<String> json(IdempotentRequests.Outcome outcome) {
        var response = ResponseEntity.status(outcome.status()).contentType(MediaType.APPLICATION_JSON);
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }
}
