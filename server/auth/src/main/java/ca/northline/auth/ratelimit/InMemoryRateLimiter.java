package ca.northline.auth.ratelimit;

import ca.northline.auth.application.LimitScope;
import ca.northline.auth.application.RateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The same algorithm as {@code attempt.lua}, in this JVM only: counters are per instance and vanish on restart. For
 * {@code local} runs without Valkey and for tests; refused under {@code staging} and {@code prod}.
 */
final class InMemoryRateLimiter implements RateLimiter {

    private static final int SWEEP_ABOVE = 10_000;

    private final Clock clock;
    private final Map<String, Counter> counters = new HashMap<>();

    /** One subject's state. */
    private static final class Counter {
        final Deque<Instant> events = new ArrayDeque<>();

        @Nullable
        Instant lockedUntil;

        int strikes;

        @Nullable
        Instant strikesUntil;
    }

    InMemoryRateLimiter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized Decision check(List<Limit> limits) {
        var wait = lockedFor(limits, clock.instant());
        return wait.isZero() ? Decision.ALLOWED : Decision.denied(wait, EnumSet.noneOf(LimitScope.class));
    }

    @Override
    public synchronized Decision record(List<Limit> limits) {
        var now = clock.instant();
        var wait = lockedFor(limits, now);
        if (!wait.isZero()) {
            return Decision.denied(wait, EnumSet.noneOf(LimitScope.class));
        }
        sweep(now);
        var lockedFor = Duration.ZERO;
        var newly = EnumSet.noneOf(LimitScope.class);
        for (var limit : limits) {
            var counter = counters.computeIfAbsent(RedisRateLimiter.key(limit, ""), _ -> new Counter());
            var from = now.minus(limit.rule().window());
            while (!counter.events.isEmpty() && !counter.events.peekFirst().isAfter(from)) {
                counter.events.pollFirst();
            }
            counter.events.addLast(now);
            if (counter.events.size() >= limit.threshold()) {
                if (counter.strikesUntil == null || !counter.strikesUntil.isAfter(now)) {
                    counter.strikes = 0;
                }
                counter.strikes++;
                counter.strikesUntil = now.plus(limit.backoffMemory());
                var duration = backoff(limit, counter.strikes);
                counter.lockedUntil = now.plus(duration);
                counter.events.clear();
                newly.add(limit.scope());
                lockedFor = duration.compareTo(lockedFor) > 0 ? duration : lockedFor;
            }
        }
        return newly.isEmpty() ? Decision.ALLOWED : Decision.denied(lockedFor, newly);
    }

    @Override
    public synchronized void reset(List<Limit> limits) {
        limits.forEach(l -> {
            var counter = counters.get(RedisRateLimiter.key(l, ""));
            if (counter != null) {
                counter.events.clear();
                counter.strikes = 0;
                counter.strikesUntil = null;
            }
        });
    }

    private Duration lockedFor(List<Limit> limits, Instant now) {
        var wait = Duration.ZERO;
        for (var limit : limits) {
            var counter = counters.get(RedisRateLimiter.key(limit, ""));
            var until = counter == null ? null : counter.lockedUntil;
            if (until != null && until.isAfter(now)) {
                var left = Duration.between(now, until);
                wait = left.compareTo(wait) > 0 ? left : wait;
            }
        }
        return wait;
    }

    /** {@code lockout × 2^(strikes-1)}, capped at {@code maxLockout}. */
    private static Duration backoff(Limit limit, int strikes) {
        var max = limit.rule().maxLockout().toMillis();
        var millis = limit.rule().lockout().toMillis();
        for (int i = 1; i < strikes && millis < max; i++) {
            millis *= 2;
        }
        return Duration.ofMillis(Math.min(millis, max));
    }

    private void sweep(Instant now) {
        if (counters.size() > SWEEP_ABOVE) {
            counters.values()
                    .removeIf(c -> c.events.isEmpty()
                            && (c.lockedUntil == null || !c.lockedUntil.isAfter(now))
                            && (c.strikesUntil == null || !c.strikesUntil.isAfter(now)));
        }
    }
}
