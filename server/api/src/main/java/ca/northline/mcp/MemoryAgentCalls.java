package ca.northline.mcp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** {@link AgentCalls} in this process ({@code northline.mcp.store=memory}: local, test — one instance only). */
final class MemoryAgentCalls implements AgentCalls {

    private record Entry(String value, Instant expires) {}

    private final Clock clock;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, long[]> counters = new ConcurrentHashMap<>();

    MemoryAgentCalls(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean allow(String key, int perMinute) {
        var minute = clock.millis() / 60_000;
        if (counters.size() > 10_000) {
            counters.values().removeIf(c -> c[0] != minute);
        }
        var counter = counters.compute(key, (_, c) -> c == null || c[0] != minute ? new long[] {minute, 0} : c);
        synchronized (counter) {
            return ++counter[1] <= perMinute;
        }
    }

    @Override
    public boolean putIfAbsent(String key, String value, Duration ttl) {
        var now = clock.instant();
        entries.values().removeIf(e -> e.expires().isBefore(now));
        var stored = new AtomicBoolean();
        entries.compute(key, (_, e) -> {
            if (e == null || e.expires().isBefore(now)) {
                stored.set(true);
                return new Entry(value, now.plus(ttl));
            }
            return e;
        });
        return stored.get();
    }

    @Override
    public Optional<String> get(String key) {
        var entry = entries.get(key);
        return entry == null || entry.expires().isBefore(clock.instant())
                ? Optional.empty()
                : Optional.of(entry.value());
    }

    @Override
    public boolean remove(String key) {
        var entry = entries.remove(key);
        return entry != null && !entry.expires().isBefore(clock.instant());
    }
}
