package ca.northline.orders.web;

import ca.northline.orders.application.TipCourier;
import ca.northline.payments.api.CourierTips;
import ca.northline.payments.api.CourierTips.Tip;
import ca.northline.payments.api.IdempotentRequests;
import ca.northline.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

/**
 * Tipping the courier after the delivery (mobile gaps part 2): a goods or food order brought by a courier, within 7 days,
 * once; 100 % goes to the courier. Starting is money-moving: {@code Idempotency-Key} required.
 *
 * <pre>
 * GET  /api/v1/me/orders/{id}/tips                       {items: [Tip], canTip, courierFirstName}
 * POST /api/v1/me/orders/{id}/tips                       {kind: amount|percent, value} → 201 {tip, provider, publishableKey}
 * POST /api/v1/me/orders/{id}/tips/{tipId}/confirm       the card authorized it → Tip (allocated to the courier)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/me/orders/{orderId}/tips")
@RequiredArgsConstructor
class CourierTipController {

    private final TipCourier tipping;
    private final IdempotentRequests idempotent;

    record TipRequest(
            @NotBlank(message = CourierTips.TOO_MUCH)
            @Pattern(regexp = "amount|percent", message = CourierTips.TOO_MUCH)
            String kind,

            @NotNull(message = CourierTips.TOO_MUCH) Long value) {}

    @GetMapping
    TipCourier.Tips tips(@PathVariable String orderId, CurrentUser user) {
        return tipping.tips(user.userId(), orderId);
    }

    @PostMapping
    ResponseEntity<String> start(
            @PathVariable String orderId,
            @Valid @RequestBody TipRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) @Nullable String key,
            CurrentUser user) {
        var outcome = idempotent.run(
                "consumer:%s:tip:%s".formatted(user.userId(), orderId),
                key,
                body,
                HttpStatus.CREATED.value(),
                () -> tipping.start(user.userId(), orderId, body.kind(), body.value(), key));
        var response = ResponseEntity.status(outcome.status()).contentType(MediaType.APPLICATION_JSON);
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }

    @PostMapping("/{tipId}/confirm")
    Tip confirm(@PathVariable String orderId, @PathVariable String tipId, CurrentUser user) {
        return tipping.confirm(user.userId(), orderId, tipId);
    }
}
