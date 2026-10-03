package ca.northline.hire.application;

import ca.northline.availability.api.ProviderSlots;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * S-119: a category's provider cards each show the provider's next free start ("Next: today 14:30"), and computing one
 * reads the provider's calendar day by day — weekly hours, time off, holidays, jobs, connected calendars, holds: about
 * twenty queries per provider, so a category with 150 providers took ~3,000 queries and ~2.7 s per page view.
 *
 * <p>Each answer is kept per provider and job length (per api instance) and is fresh for {@link #TTL}; after that the
 * card shows the kept answer while a background read replaces it — one read per provider at a time, at most
 * {@link #BACKGROUND} reads at once for the whole instance, so a minute's worth of expired cards never takes the
 * connection pool. A kept start that has passed is read again at once; a provider seen for the first time is read in
 * the request (the first page after a deploy is the slow one). Never read in parallel inside the request: the request
 * holds its own connection, and readers waiting for the pool behind it deadlock (S-119's first local run).
 *
 * <p>A card may offer a slot taken in the last minute — the provider's calendar, which the customer books from, is
 * always live and a taken slot answers {@code slot_taken}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class NextFreeSlots {

    static final Duration TTL = Duration.ofSeconds(60);
    static final int BACKGROUND = 2;
    static final int MAX_ENTRIES = 20_000;

    private record Key(String merchantId, int durationMin) {}

    private record Entry(@Nullable Instant next, Instant freshUntil) {}

    private final ProviderSlots slots;
    private final Clock clock;
    private final Semaphore readers = new Semaphore(BACKGROUND);
    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
    private final Set<Key> refreshing = ConcurrentHashMap.newKeySet();
    /** Runs a refresh after the answer was given: a virtual thread (tests: at once, or queued). */
    Consumer<Runnable> background =
            task -> Thread.ofVirtual().name("next-free-slot").start(task);

    /** The next free start for a job of {@code durationMin}; empty = none within the booking horizon. */
    Optional<Instant> next(String merchantId, int durationMin) {
        var key = new Key(merchantId, durationMin);
        var now = clock.instant();
        var entry = entries.get(key);
        var kept = entry == null ? null : entry.next();
        if (entry == null || (kept != null && kept.isBefore(now))) {
            return Optional.ofNullable(read(key));
        }
        if (!entry.freshUntil().isAfter(now) && refreshing.add(key)) {
            background.accept(() -> {
                readers.acquireUninterruptibly(); // waits without holding a connection
                try {
                    read(key);
                } catch (RuntimeException e) {
                    log.warn("Next free slot of {} not refreshed", key.merchantId(), e);
                } finally {
                    readers.release();
                    refreshing.remove(key);
                }
            });
        }
        return Optional.ofNullable(kept);
    }

    private @Nullable Instant read(Key key) {
        var now = clock.instant();
        if (entries.size() >= MAX_ENTRIES) {
            entries.values().removeIf(e -> e.freshUntil().plus(TTL).isBefore(now));
        }
        var next = slots.next(key.merchantId(), key.durationMin()).orElse(null);
        entries.put(key, new Entry(next, now.plus(TTL)));
        return next;
    }
}
