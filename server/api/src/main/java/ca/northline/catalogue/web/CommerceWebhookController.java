package ca.northline.catalogue.web;

import ca.northline.catalogue.application.CommerceCatalogSource.Unverified;
import ca.northline.catalogue.application.CommerceCatalogSource.WebhookRequest;
import ca.northline.catalogue.application.CommerceSettings;
import ca.northline.catalogue.application.SyncIntegrations.ReceiveCommerceWebhook;
import ca.northline.catalogue.application.SyncIntegrations.ReceiveCommerceWebhook.Receipt;
import ca.northline.shared.WebhookRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;

/**
 * Platform webhooks (S-35): {@code POST /api/v1/webhooks/commerce/<shopify|square|lightspeed>}. Public — no token,
 * session or CSRF (open in {@code SecurityConfig}, routed on the api host, docs/runbooks/edge.md) — and verified by the
 * platform's HMAC over the raw body; a repeated delivery is acknowledged and ignored. Rate-limited per client address.
 */
@RestController
class CommerceWebhookController {

    static final int MAX_BODY = 1024 * 1024;

    private final ReceiveCommerceWebhook receive;
    private final CommerceSettings settings;
    private final WebhookRateLimiter limiter;

    CommerceWebhookController(
            ReceiveCommerceWebhook receive,
            CommerceSettings settings,
            Clock clock,
            @Value("${northline.commerce.webhook-rate-limit:600}") int perMinute) {
        this.receive = receive;
        this.settings = settings;
        this.limiter = new WebhookRateLimiter(perMinute, clock);
    }

    @PostMapping(path = "/api/v1/webhooks/commerce/{provider}", consumes = "*/*")
    ResponseEntity<?> receive(
            @PathVariable String provider,
            @RequestBody(required = false) byte @Nullable [] body,
            HttpServletRequest request) {
        var p = CommerceController.provider(provider);
        if (!limiter.allow(request.getRemoteAddr())) {
            var problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "Too many webhook requests.");
            problem.setProperty("code", "rate_limited");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "60")
                    .body(problem);
        }
        if (body == null || body.length == 0 || body.length > MAX_BODY) {
            return refused();
        }
        var headers = new HashMap<String, String>();
        for (var name : Collections.list(request.getHeaderNames())) {
            headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        try {
            var outcome = receive.receive(p, new WebhookRequest(Map.copyOf(headers), body, settings.webhookUrl(p)));
            return ResponseEntity.ok(Map.of("received", true, "duplicate", outcome == Receipt.DUPLICATE));
        } catch (Unverified | JacksonException _) {
            return refused();
        }
    }

    private static ResponseEntity<ProblemDetail> refused() {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "This webhook can't be verified.");
        problem.setType(URI.create("https://northline.ca/problems/invalid-webhook"));
        problem.setProperty("code", "invalid_webhook");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }
}
