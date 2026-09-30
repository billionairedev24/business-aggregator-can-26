package ca.northline.payments.web;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed one-minute window per client address for the (unauthenticated) webhook endpoints: Stripe delivers well under
 * the limit, anything above it is refused with 429 (Stripe retries with backoff). Per api instance; enough to stop a
 * flood of unsigned requests from costing HMAC checks and database work.
 */
final class WebhookRateLimiter {

    private record Window(long minute, AtomicInteger count) {}

    private final int perMinute;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    WebhookRateLimiter(int perMinute, Clock clock) {
        this.perMinute = perMinute;
        this.clock = clock;
    }

    /** Counts a request; false when the address is over the limit for the current minute. */
    boolean allow(String address) {
        var minute = clock.millis() / 60_000;
        if (windows.size() > 10_000) {
            windows.values().removeIf(w -> w.minute() != minute);
        }
        var window = windows.compute(
                address, (_, w) -> w == null || w.minute() != minute ? new Window(minute, new AtomicInteger()) : w);
        return window.count().incrementAndGet() <= perMinute;
    }
}
