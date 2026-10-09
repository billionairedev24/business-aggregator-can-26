package ca.northline.orders.web;

import ca.northline.orders.application.FoodCheckout;
import ca.northline.orders.application.FoodCheckout.PlaceFoodOrder;
import ca.northline.orders.application.FoodCheckout.QuoteFoodOrder;
import ca.northline.orders.application.FoodCheckout.StartFoodOrder;
import ca.northline.orders.application.FoodCheckout.Totals;
import ca.northline.orders.application.FoodCheckout.TrackFoodOrder;
import ca.northline.orders.application.FoodCheckout.Tracking;
import ca.northline.payments.api.IdempotentRequests;
import ca.northline.shared.security.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The signed-in customer's food orders (S-57). Any signed-in token is enough to read and quote; paying needs an
 * {@code Idempotency-Key}, and a session signed in with a phone code only (no {@code acr=mfa}) also needs a fresh
 * step-up proof ({@code X-Step-Up}; S-51's rule: 403 {@code step_up_required}, or {@code second_factor_required}
 * when the account has no second factor to step up with).
 *
 * <pre>
 * POST /api/v1/me/food-orders/quote           the order's totals (tax estimated from the province's rates)
 * POST /api/v1/me/food-orders                 201: the checkout + its card payment (Stripe client secret)
 * POST /api/v1/me/food-orders/{id}/confirm    the card is authorized → escrow held, order placed (order.placed)
 * GET  /api/v1/me/food-orders/{id}            tracking
 * GET  /api/v1/me/food-orders/{id}/events     tracking as text/event-stream (S-88: kitchen steps and courier moves)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/me/food-orders")
@RequiredArgsConstructor
class FoodOrderController {

    private final QuoteFoodOrder quote;
    private final StartFoodOrder start;
    private final PlaceFoodOrder place;
    private final TrackFoodOrder track;
    private final IdempotentRequests idempotent;
    private final OrderStreams streams;

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String STEP_UP = "X-Step-Up";

    record ItemLine(
            @Nullable String itemId,
            int qty,
            @Nullable List<String> optionIds,
            @Nullable String note) {}

    record ComboLine(
            @Nullable String comboId, int qty, @Nullable List<String> itemIds) {}

    record Tip(@Nullable String kind, long value) {}

    record Delivery(
            @Nullable String street,
            @Nullable String unit,
            @Nullable String city,
            @Nullable String province,
            @Nullable String postalCode,
            @Nullable Double lat,
            @Nullable Double lng,
            @Nullable String zoneId,
            @Nullable String zone,
            @Nullable String dropoff,
            @Nullable String note,
            @Nullable List<String> extras) {}

    record OrderRequest(
            @Nullable String merchantId,
            @Nullable String mode,
            @Nullable Instant scheduledFor,
            @Nullable List<ItemLine> items,
            @Nullable List<ComboLine> combos,
            @Nullable Tip tip,
            @Nullable Delivery delivery,
            @Nullable String promoCode,
            @Nullable Boolean usePoints) {}

    @PostMapping("/quote")
    Totals quote(@RequestBody OrderRequest body, CurrentUser user) {
        return quote.quote(user.userId(), FoodRequests.order(body));
    }

    @PostMapping
    ResponseEntity<String> start(
            @RequestBody OrderRequest body,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) @Nullable String key,
            @RequestHeader(value = STEP_UP, required = false) @Nullable String stepUp,
            CurrentUser user,
            Locale locale) {
        var order = FoodRequests.order(body);
        return json(idempotent.run(
                "consumer:" + user.userId() + ":food-order",
                key,
                body,
                HttpStatus.CREATED.value(),
                () -> start.start(user.userId(), user.mfa(), stepUp, order, Objects.requireNonNull(key), locale)));
    }

    @PostMapping("/{orderId}/confirm")
    ResponseEntity<String> confirm(
            @PathVariable String orderId,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) @Nullable String key,
            CurrentUser user) {
        return json(idempotent.run(
                "consumer:" + user.userId() + ":food-order:" + orderId,
                key,
                null,
                HttpStatus.OK.value(),
                () -> place.place(user.userId(), orderId)));
    }

    private static ResponseEntity<String> json(IdempotentRequests.Outcome outcome) {
        var response = ResponseEntity.status(outcome.status()).contentType(MediaType.APPLICATION_JSON);
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }

    @GetMapping("/{orderId}")
    Tracking track(@PathVariable String orderId, CurrentUser user) {
        return track.track(user.userId(), orderId);
    }

    /** S-88: the tracking as a stream (event {@code food}), pushed on every kitchen step and courier move. */
    @GetMapping(path = "/{orderId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(@PathVariable String orderId, CurrentUser user) {
        return streams.open(orderId, "food", () -> track.track(user.userId(), orderId));
    }

    /** Maps the request onto the use case's command (missing lists = empty). */
    static final class FoodRequests {
        private FoodRequests() {}

        static FoodCheckout.Order order(OrderRequest b) {
            var d = b.delivery();
            return new FoodCheckout.Order(
                    Objects.requireNonNullElse(b.merchantId(), ""),
                    Objects.requireNonNullElse(b.mode(), ""),
                    b.scheduledFor(),
                    Objects.requireNonNullElse(b.items(), List.<ItemLine>of()).stream()
                            .map(i -> new FoodCheckout.ItemLine(
                                    Objects.requireNonNullElse(i.itemId(), ""),
                                    i.qty(),
                                    Objects.requireNonNullElse(i.optionIds(), List.<String>of()),
                                    i.note()))
                            .toList(),
                    Objects.requireNonNullElse(b.combos(), List.<ComboLine>of()).stream()
                            .map(c -> new FoodCheckout.ComboLine(
                                    Objects.requireNonNullElse(c.comboId(), ""),
                                    c.qty(),
                                    Objects.requireNonNullElse(c.itemIds(), List.<String>of())))
                            .toList(),
                    b.tip() == null
                            ? FoodCheckout.Tip.NONE
                            : new FoodCheckout.Tip(
                                    Objects.requireNonNullElse(b.tip().kind(), "none"),
                                    b.tip().value()),
                    d == null || d.street() == null || d.lat() == null || d.lng() == null
                            ? null
                            : new FoodCheckout.Delivery(
                                    d.street(),
                                    d.unit(),
                                    d.city(),
                                    Objects.requireNonNullElse(d.province(), ""),
                                    d.postalCode(),
                                    d.lat(),
                                    d.lng(),
                                    d.zoneId(),
                                    d.zone(),
                                    Objects.requireNonNullElse(d.dropoff(), "hand"),
                                    d.note(),
                                    Objects.requireNonNullElse(d.extras(), List.<String>of())),
                    b.promoCode(),
                    Boolean.TRUE.equals(b.usePoints()));
        }
    }
}
