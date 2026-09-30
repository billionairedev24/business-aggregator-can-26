package ca.northline.worker.webhooks;

import ca.northline.platform.WebhookSecretBox;
import com.github.f4b6a3.ulid.UlidCreator;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Delivers queued webhooks, <b>per endpoint</b> (S-33). Each tick takes up to {@code maxInFlight} endpoints that have
 * something due and nobody delivering to them (a lease row in {@code webhook_endpoints}, shared by every worker
 * replica), and drains each on its own virtual thread: one request in flight per endpoint, up to {@code batch}
 * deliveries per lease. A slow or dead endpoint therefore only ever delays itself — never other merchants' endpoints,
 * and never a Kafka partition (the consumer only queues rows). A replica that dies mid-delivery leaves its lease to
 * expire; the delivery is still pending and is sent again (at-least-once: receivers dedupe on the event id).
 *
 * <p>After each attempt, in one transaction: the attempt row, the delivery's state (succeeded, pending with its next
 * attempt per {@link RetrySchedule}, or failed after the last), the endpoint's health and — when it has failed for
 * {@code disableAfter} with at least {@code disableMinFailures} failures in a row — turning it off.
 */
@Slf4j
public final class WebhookDispatcher {

    public static final String DELIVERED = "northline.webhooks.deliveries";
    public static final String DISABLED = "northline.webhooks.disabled";

    public static final String EVENT_ID = "Northline-Event-Id";
    public static final String EVENT_TYPE = "Northline-Event-Type";
    public static final String DELIVERY_ID = "Northline-Delivery-Id";
    public static final String ATTEMPT = "Northline-Delivery-Attempt";

    private final WebhookStore store;
    private final WebhookTransport transport;
    private final WebhookPayloads payloads;
    private final RetrySchedule retry;
    private final @Nullable WebhookSecretBox secrets;
    private final WebhookProperties settings;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final MeterRegistry meters;
    private final String owner = "worker-" + UlidCreator.getMonotonicUlid();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private volatile boolean stopping;

    public WebhookDispatcher(
            WebhookStore store,
            WebhookTransport transport,
            WebhookPayloads payloads,
            RetrySchedule retry,
            @Nullable WebhookSecretBox secrets,
            WebhookProperties settings,
            TransactionOperations transactions,
            Clock clock,
            MeterRegistry meters) {
        this.store = store;
        this.transport = transport;
        this.payloads = payloads;
        this.retry = retry;
        this.secrets = secrets;
        this.settings = settings;
        this.transactions = transactions;
        this.clock = clock;
        this.meters = meters;
    }

    /** One tick: leases due endpoints and starts a virtual thread per endpoint. Returns the endpoints started. */
    public List<Thread> dispatch() {
        var started = new ArrayList<Thread>();
        var free = settings.maxInFlight() - inFlight.size();
        if (stopping || free <= 0) {
            return started;
        }
        var now = clock.instant();
        for (var endpointId : store.dueEndpoints(now, free)) {
            if (!inFlight.add(endpointId)) {
                continue;
            }
            if (!store.lease(endpointId, owner, now, now.plus(settings.lease()))) {
                inFlight.remove(endpointId);
                continue;
            }
            started.add(Thread.ofVirtual().name("webhook-" + endpointId).start(() -> {
                try {
                    drain(endpointId);
                } catch (RuntimeException e) {
                    log.error("Webhook delivery to endpoint {} stopped: {}", endpointId, e.toString(), e);
                } finally {
                    store.release(endpointId, owner);
                    inFlight.remove(endpointId);
                }
            }));
        }
        return started;
    }

    /** Stops taking new endpoints (shutdown); deliveries in flight finish, the rest wait for the next replica. */
    public void stop() {
        stopping = true;
    }

    private void drain(String endpointId) {
        for (var i = 0; i < settings.batch() && !stopping; i++) {
            var now = clock.instant();
            if (i > 0 && !store.renew(endpointId, owner, now.plus(settings.lease()))) {
                return;
            }
            var due = store.nextDue(endpointId, now).orElse(null);
            var target = due == null ? null : store.target(endpointId).orElse(null);
            if (due == null || target == null || !target.active()) {
                return;
            }
            try (var _ = MDC.putCloseable("eventId", due.eventId());
                    var _ = MDC.putCloseable("eventType", due.eventType());
                    var _ = MDC.putCloseable("consumer", "webhooks")) {
                deliver(target, due);
            }
        }
    }

    private void deliver(WebhookStore.Target target, WebhookStore.Due due) {
        var attempt = due.attempts() + 1;
        var at = clock.instant();
        var payload = due.payload();
        if (payload == null) {
            payload = payloads.test(due.eventId(), target.merchantId(), target.id(), due.createdAt())
                    .payload()
                    .toString();
            store.setPayload(due.id(), payload);
        }
        var result = send(target, due, attempt, at, payload.getBytes(StandardCharsets.UTF_8));
        transactions.executeWithoutResult(_ -> record(target, due, attempt, at, result));
    }

    private WebhookTransport.Result send(
            WebhookStore.Target target, WebhookStore.Due due, int attempt, Instant at, byte[] body) {
        List<String> keys;
        try {
            keys = signingSecrets(target, at);
        } catch (IllegalStateException e) {
            log.error("Webhook endpoint {}: {}", target.id(), e.getMessage());
            return new WebhookTransport.Result(null, 0, "cannot read the signing secret", null);
        }
        if (keys.isEmpty()) {
            return new WebhookTransport.Result(null, 0, "no signing secret: rotate the secret in Studio", null);
        }
        URI url;
        try {
            url = URI.create(target.url());
        } catch (IllegalArgumentException e) {
            return new WebhookTransport.Result(null, 0, "invalid URL", null);
        }
        var headers = new LinkedHashMap<String, String>();
        headers.put(WebhookSigner.HEADER, WebhookSigner.header(at, body, keys));
        headers.put(EVENT_ID, due.eventId());
        headers.put(EVENT_TYPE, due.eventType());
        headers.put(DELIVERY_ID, due.id());
        headers.put(ATTEMPT, Integer.toString(attempt));
        return transport.post(url, Map.copyOf(headers), body);
    }

    /** The current secret, and the previous one while its rotation overlap lasts. */
    private List<String> signingSecrets(WebhookStore.Target target, Instant at) {
        var box = secrets;
        if (box == null) {
            throw new IllegalStateException("WEBHOOK_SECRET_KEY is not set");
        }
        var keys = new ArrayList<String>(2);
        var current = target.secret();
        if (current != null) {
            keys.add(box.decrypt(current));
        }
        var previous = target.previousSecret();
        var until = target.previousUntil();
        if (previous != null && until != null && until.isAfter(at)) {
            keys.add(box.decrypt(previous));
        }
        return keys;
    }

    private void record(
            WebhookStore.Target target, WebhookStore.Due due, int attempt, Instant at, WebhookTransport.Result result) {
        store.recordAttempt(due.id(), attempt, at, result);
        if (result.success()) {
            store.succeeded(due.id(), target.id(), attempt, at, result);
            count(due, "succeeded");
            log.debug("webhook {} → {} {} (attempt {})", due.eventType(), target.url(), result.status(), attempt);
            return;
        }
        var next = retry.after(attempt).map(at::plus).orElse(null);
        count(due, next == null ? "failed" : "retrying");
        log.info(
                "webhook {} → endpoint {} attempt {} failed: {}{}",
                due.eventType(),
                target.id(),
                attempt,
                result.outcome(),
                next == null ? " — giving up" : " — next at " + next);
        var health =
                store.failed(due.id(), target.id(), attempt, at, result, next).orElse(null);
        if (health != null
                && health.consecutiveFailures() >= settings.disableMinFailures()
                && !health.failingSince().isAfter(at.minus(settings.disableAfter()))
                && store.disable(target.id(), UlidCreator.getMonotonicUlid().toString(), at, result.outcome())) {
            meters.counter(DISABLED).increment();
            log.warn(
                    "WEBHOOK-DISABLED endpoint {} of merchant {}: failing since {} ({} failures in a row, last: {})",
                    target.id(),
                    target.merchantId(),
                    health.failingSince(),
                    health.consecutiveFailures(),
                    result.outcome());
        }
    }

    private void count(WebhookStore.Due due, String outcome) {
        meters.counter(DELIVERED, "type", due.eventType(), "outcome", outcome).increment();
    }
}
