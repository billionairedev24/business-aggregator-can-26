package ca.northline.search.integration;

import ca.northline.search.application.SearchCache;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** The hot-query cache in this process (profiles {@code local} and {@code test}: no Redis). Bounded, expiring. */
final class MemorySearchCache implements SearchCache {

    private static final int MAX_ENTRIES = 1_000;

    private record Entry(String json, Instant expires) {}

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    MemorySearchCache(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Optional<String> get(String key) {
        var entry = entries.get(key);
        if (entry == null || !entry.expires().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(entry.json());
    }

    @Override
    public void put(String key, String json, Duration ttl) {
        if (entries.size() >= MAX_ENTRIES) {
            var now = clock.instant();
            entries.values().removeIf(e -> !e.expires().isAfter(now));
            if (entries.size() >= MAX_ENTRIES) {
                entries.clear();
            }
        }
        entries.put(key, new Entry(json, clock.instant().plus(ttl)));
    }
}
