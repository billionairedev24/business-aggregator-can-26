package ca.northline.auth.replay;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The same in this JVM only — local runs without Valkey and tests; refused under staging/prod. */
final class InMemoryReplayStore implements ReplayStore {

    private static final int SWEEP_ABOVE = 10_000;

    private record Entry(String value, Instant until) {}

    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    InMemoryReplayStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean firstUse(String key, Duration ttl) {
        var now = clock.instant();
        sweep(now);
        var fresh = new boolean[1];
        entries.compute(key, (_, e) -> {
            fresh[0] = e == null || e.until().isBefore(now);
            return fresh[0] ? new Entry("1", now.plus(ttl)) : e;
        });
        return fresh[0];
    }

    @Override
    public String shared(String key, Duration ttl) {
        var now = clock.instant();
        sweep(now);
        return entries.compute(
                        key,
                        (_, e) -> e == null || e.until().isBefore(now)
                                ? new Entry(RedisReplayStore.random(), now.plus(ttl))
                                : e)
                .value();
    }

    private void sweep(Instant now) {
        if (entries.size() > SWEEP_ABOVE) {
            entries.values().removeIf(e -> e.until().isBefore(now));
        }
    }
}
