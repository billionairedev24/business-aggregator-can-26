package ca.northline.shared;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * A small per-instance read-through cache for public read models that are the same for everyone (engineering
 * follow-ups, S-119 F5: the landing pages read every business of a city on each view). Entries live {@code ttl}; {@link
 * #invalidateAll()} drops them at once when something they show changed (a business approved, hidden, paused …).
 *
 * <p>No locks: on a miss each caller loads for itself. A loader runs inside the caller's transaction, holding a
 * database connection — waiting on another caller's load here would be the S-119 F1 pool deadlock. A load that began
 * before an invalidation is returned to its caller but not kept, so a change is never hidden for a whole {@code ttl}.
 */
public final class ShortCache<K, V> {

    private record Entry<V>(V value, Instant expires) {}

    private final ConcurrentHashMap<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private final Clock clock;
    private final Duration ttl;
    private final int maxEntries;

    public ShortCache(Clock clock, Duration ttl, int maxEntries) {
        if (ttl.isNegative() || maxEntries < 1) {
            throw new IllegalArgumentException("ttl " + ttl + ", maxEntries " + maxEntries);
        }
        this.clock = clock;
        this.ttl = ttl;
        this.maxEntries = maxEntries;
    }

    /** The kept value for {@code key}, or {@code load}'s (kept for {@code ttl}). A zero ttl turns the cache off. */
    public V get(K key, Supplier<V> load) {
        var now = clock.instant();
        var entry = entries.get(key);
        if (entry != null && entry.expires().isAfter(now)) {
            return entry.value();
        }
        var before = generation.get();
        var value = load.get();
        if (ttl.isZero()) {
            return value;
        }
        if (entries.size() >= maxEntries) {
            entries.values().removeIf(e -> !e.expires().isAfter(now));
            if (entries.size() >= maxEntries) {
                entries.clear();
            }
        }
        entries.put(key, new Entry<>(value, now.plus(ttl)));
        if (generation.get() != before) {
            entries.remove(key); // invalidated while loading: this value may predate the change
        }
        return value;
    }

    /** Drops every entry (and any load still running). */
    public void invalidateAll() {
        generation.incrementAndGet();
        entries.clear();
    }

    public int size() {
        return entries.size();
    }
}
