package ca.northline.availability.web;

import ca.northline.availability.application.CalendarUseCases.ReceiveCalendarNotifications;
import ca.northline.availability.application.CalendarUseCases.ReceiveCalendarNotifications.GoogleNotification;
import ca.northline.availability.application.CalendarUseCases.ReceiveCalendarNotifications.GraphNotification;
import ca.northline.availability.application.CalendarUseCases.ReceiveCalendarNotifications.InvalidNotification;
import ca.northline.availability.application.CalendarUseCases.ReceiveCalendarNotifications.Outcome;
import ca.northline.shared.WebhookRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calendar change notifications (S-32). Public — no token, session or CSRF (the path is open in {@code SecurityConfig}
 * and routed on the api host by the Gateway, docs/runbooks/edge.md) — and verified per channel: the secret Northline
 * gave the provider when it opened the channel must come back. Answers as soon as the notification is recorded; the
 * read happens asynchronously.
 *
 * <pre>
 * POST /api/v1/webhooks/calendar/google                 Google push (headers only)
 * POST /api/v1/webhooks/calendar/microsoft[?validationToken=…]            Graph change notifications
 * POST /api/v1/webhooks/calendar/microsoft/lifecycle[?validationToken=…]  Graph lifecycle notifications
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/webhooks/calendar")
class CalendarWebhookController {

    static final int MAX_VALIDATION_TOKEN = 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ReceiveCalendarNotifications receive;
    private final WebhookRateLimiter limiter;

    CalendarWebhookController(
            ReceiveCalendarNotifications receive,
            Clock clock,
            @Value("${northline.calendar.webhook-rate-limit:600}") int perMinute) {
        this.receive = receive;
        this.limiter = new WebhookRateLimiter(perMinute, clock);
    }

    @PostMapping(path = "/google", consumes = "*/*")
    ResponseEntity<?> google(
            @RequestHeader(name = "X-Goog-Channel-ID", required = false) @Nullable String channelId,
            @RequestHeader(name = "X-Goog-Channel-Token", required = false) @Nullable String token,
            @RequestHeader(name = "X-Goog-Resource-ID", required = false) @Nullable String resourceId,
            @RequestHeader(name = "X-Goog-Resource-State", required = false) @Nullable String resourceState,
            @RequestHeader(name = "X-Goog-Message-Number", required = false) @Nullable String messageNumber,
            HttpServletRequest request) {
        if (!limiter.allow(request.getRemoteAddr())) {
            return tooMany();
        }
        try {
            var outcome =
                    receive.google(new GoogleNotification(channelId, token, resourceId, resourceState, messageNumber));
            return ResponseEntity.ok(Map.of("received", true, "duplicate", outcome == Outcome.DUPLICATE));
        } catch (InvalidNotification _) {
            return refused();
        }
    }

    @PostMapping(
            path = {"/microsoft", "/microsoft/lifecycle"},
            consumes = "*/*")
    ResponseEntity<?> microsoft(
            @RequestParam(required = false) @Nullable String validationToken,
            @RequestBody(required = false) @Nullable String body,
            HttpServletRequest request) {
        if (!limiter.allow(request.getRemoteAddr())) {
            return tooMany();
        }
        if (validationToken != null) {
            // Graph's handshake while we create a subscription: echo the token as text/plain within 10 s
            if (validationToken.length() > MAX_VALIDATION_TOKEN || !receive.acceptsValidation()) {
                return refused();
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .header("X-Content-Type-Options", "nosniff")
                    .body(validationToken);
        }
        List<GraphNotification> batch;
        try {
            batch = parse(body == null ? "" : body);
        } catch (JacksonException _) {
            return refused();
        }
        try {
            receive.microsoft(batch);
            return ResponseEntity.accepted().build();
        } catch (InvalidNotification _) {
            return refused();
        }
    }

    static List<GraphNotification> parse(String body) {
        var items = new ArrayList<GraphNotification>();
        for (JsonNode n : JSON.readTree(body).path("value")) {
            var data = n.path("resourceData");
            items.add(new GraphNotification(
                    text(n, "subscriptionId"),
                    text(n, "clientState"),
                    text(n, "changeType"),
                    text(data, "id"),
                    text(data, "@odata.etag"),
                    text(n, "lifecycleEvent")));
        }
        return items;
    }

    private static @Nullable String text(JsonNode node, String field) {
        var v = node.path(field);
        return v.isString() ? v.asString() : null;
    }

    private static ResponseEntity<ProblemDetail> refused() {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "This notification can't be verified.");
        problem.setType(URI.create("https://northline.ca/problems/invalid-notification"));
        problem.setProperty("code", "invalid_notification");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    private static ResponseEntity<ProblemDetail> tooMany() {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "Too many webhook requests.");
        problem.setType(URI.create("https://northline.ca/problems/rate-limited"));
        problem.setProperty("code", "rate_limited");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "60")
                .body(problem);
    }
}
