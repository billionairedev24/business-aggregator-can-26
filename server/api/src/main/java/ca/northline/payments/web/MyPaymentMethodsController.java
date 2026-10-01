package ca.northline.payments.web;

import ca.northline.payments.api.PaymentSettings;
import ca.northline.payments.application.PaymentMethods.ManagePaymentMethods;
import ca.northline.payments.application.PaymentMethods.Payment;
import ca.northline.payments.application.PaymentMethods.SavedCard;
import ca.northline.payments.application.PaymentMethods.Setup;
import ca.northline.shared.ListResponse;
import ca.northline.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The consumer's payment methods and billing history (S-59, design 06 payments). Cards are saved at Stripe with a
 * SetupIntent (no charge) confirmed by Stripe.js; Northline keeps brand, last four and expiry only.
 *
 * <pre>
 * GET    /api/v1/me/payment-methods                    {provider, publishableKey, items: [card]}
 * POST   /api/v1/me/payment-methods/setup-intents      → 201 {setupIntentId, clientSecret, provider, publishableKey}
 * POST   /api/v1/me/payment-methods                    {setupIntentId} once Stripe.js confirmed it → the cards
 * POST   /api/v1/me/payment-methods/{id}/default       → the cards
 * DELETE /api/v1/me/payment-methods/{id}               → the cards (404 for a card that isn't the caller's)
 * GET    /api/v1/me/billing-history                    newest first
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
class MyPaymentMethodsController {

    static final int HISTORY = 50;

    private final ManagePaymentMethods methods;
    private final PaymentSettings settings;

    record CardsResponse(String provider, @Nullable String publishableKey, List<SavedCard> items) {}

    record ConfirmRequest(
            @NotBlank(message = "Add the card again.") String setupIntentId) {}

    private CardsResponse cards(List<SavedCard> items) {
        return new CardsResponse(settings.provider(), settings.publishableKey(), items);
    }

    @Operation(summary = "The caller's saved cards")
    @GetMapping("/payment-methods")
    ResponseEntity<CardsResponse> list(CurrentUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cards(methods.cards(user.userId())));
    }

    @Operation(summary = "Start saving a card: a SetupIntent for Stripe.js to confirm (no charge)")
    @PostMapping("/payment-methods/setup-intents")
    @ResponseStatus(HttpStatus.CREATED)
    Setup setup(CurrentUser user) {
        return methods.startSetup(user.userId());
    }

    @Operation(summary = "Keep the card a confirmed SetupIntent saved")
    @PostMapping("/payment-methods")
    CardsResponse confirm(CurrentUser user, @Valid @RequestBody ConfirmRequest body) {
        return cards(methods.confirmSetup(user.userId(), body.setupIntentId()));
    }

    @Operation(summary = "Make a saved card the default")
    @PostMapping("/payment-methods/{paymentMethodId}/default")
    CardsResponse makeDefault(CurrentUser user, @PathVariable String paymentMethodId) {
        return cards(methods.makeDefault(user.userId(), paymentMethodId));
    }

    @Operation(summary = "Remove a saved card")
    @DeleteMapping("/payment-methods/{paymentMethodId}")
    CardsResponse remove(CurrentUser user, @PathVariable String paymentMethodId) {
        return cards(methods.remove(user.userId(), paymentMethodId));
    }

    @Operation(summary = "The caller's payments, newest first")
    @GetMapping("/billing-history")
    ResponseEntity<ListResponse<Payment>> billing(CurrentUser user) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ListResponse<>(methods.billing(user.userId(), HISTORY)));
    }
}
