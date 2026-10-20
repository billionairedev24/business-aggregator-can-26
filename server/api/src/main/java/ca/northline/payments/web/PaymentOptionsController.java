package ca.northline.payments.web;

import ca.northline.payments.api.PaymentSettings;
import io.swagger.v3.oas.annotations.Operation;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-157: how a payment may be made, for every checkout that mounts Stripe's Payment Element (consumer web) or
 * PaymentSheet (app) — shop, food, bookings, quote deposits, tips. Public: the same for everyone, cached briefly.
 *
 * <pre>
 * GET /api/v1/public/payments/options    {provider: stripe|fake, applePay, googlePay}
 * </pre>
 *
 * Wallets are offered only under Stripe and only once switched on ({@code STRIPE_APPLE_PAY}, {@code
 * STRIPE_GOOGLE_PAY}, stripe.md § 14). They change nothing on the server: a wallet pays the same manual-capture
 * PaymentIntents with a card, after the same step-up.
 */
@RestController
@RequiredArgsConstructor
class PaymentOptionsController {

    private final PaymentSettings settings;

    record PaymentOptions(String provider, boolean applePay, boolean googlePay) {}

    @Operation(summary = "The payment methods checkouts may offer (cards, Apple Pay, Google Pay)")
    @GetMapping("/api/v1/public/payments/options")
    ResponseEntity<PaymentOptions> options() {
        var wallets = settings.wallets();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(new PaymentOptions(settings.provider(), wallets.applePay(), wallets.googlePay()));
    }
}
