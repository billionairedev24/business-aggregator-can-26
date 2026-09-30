package ca.northline.payments.web;

import ca.northline.payments.application.ReceiveStripeEvents;
import ca.northline.payments.application.StripeEvent;
import ca.northline.shared.WebhookRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stripe webhooks. No session, cookie or token (Stripe has none; the path is open in {@code SecurityConfig}) — the
 * {@code Stripe-Signature} is the authentication, checked against the endpoint's own secret. The body is read raw:
 * the signature covers the exact bytes. Answers 200 as soon as the event is stored (duplicates too); the work
 * happens asynchronously.
 *
 * <pre>
 * POST /api/v1/webhooks/stripe            platform events (charges, PaymentIntents, refunds, transfers, disputes)
 * POST /api/v1/webhooks/stripe/connect    connected accounts' events (account.updated, payout.*)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/webhooks/stripe")
class StripeWebhookController {

    static final String SIGNATURE = "Stripe-Signature";

    private final ReceiveStripeEvents receive;
    private final WebhookRateLimiter limiter;

    StripeWebhookController(
            ReceiveStripeEvents receive,
            Clock clock,
            @Value("${northline.payments.webhook-rate-limit:600}") int perMinute) {
        this.receive = receive;
        this.limiter = new WebhookRateLimiter(perMinute, clock);
    }

    @PostMapping(consumes = "*/*")
    ResponseEntity<?> platform(
            @RequestBody String payload,
            @RequestHeader(name = SIGNATURE, required = false) @Nullable String signature,
            HttpServletRequest request) {
        return handle(StripeEvent.Endpoint.PLATFORM, payload, signature, request);
    }

    @PostMapping(path = "/connect", consumes = "*/*")
    ResponseEntity<?> connect(
            @RequestBody String payload,
            @RequestHeader(name = SIGNATURE, required = false) @Nullable String signature,
            HttpServletRequest request) {
        return handle(StripeEvent.Endpoint.CONNECT, payload, signature, request);
    }

    private ResponseEntity<?> handle(
            StripeEvent.Endpoint endpoint, String payload, @Nullable String signature, HttpServletRequest request) {
        if (!limiter.allow(request.getRemoteAddr())) {
            var problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "Too many webhook requests.");
            problem.setType(URI.create("https://northline.ca/problems/rate-limited"));
            problem.setProperty("code", "rate_limited");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "60")
                    .body(problem);
        }
        var outcome = receive.receive(endpoint, payload, signature);
        return ResponseEntity.ok(
                Map.of("received", true, "duplicate", outcome == ReceiveStripeEvents.Outcome.DUPLICATE));
    }
}
