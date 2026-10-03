package ca.northline.hire.application;

import ca.northline.availability.api.ProviderSlots;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * S-119: a category's provider cards each show the provider's next free start ("Next: today 14:30"), and computing one
 * reads the provider's whole calendar — weekly hours, time off, holidays, jobs, connected calendars, holds: about twenty
 * queries per provider, so a list of 150 providers took ~3,000 queries per page view. Each answer is kept for
 * {@link #TTL} per provider and job length (per api instance); a start that has passed is computed again. A card may
 * therefore offer a slot that was taken in the last minute — the provider's calendar, which the customer books from, is
 * always live and a taken slot answers {@code slot_taken}.
 */
@Component
@RequiredArgsConstructor
class NextFreeSlots {

    static final Duration TTL = Duration.ofSeconds(60);
    static final int MAX_ENTRIES = 20_000;

    private record Key(String merchantId, int durationMin) {}

    private record Entry(@Nullable Instant next, Instant expiresAt) {}

    private final ProviderSlots slots;
    private final Clock clock;
    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();

    Optional<Instant> next(String merchantId, int durationMin) {
        var now = clock.instant();
        var key = new Key(merchantId, durationMin);
        var entry = entries.get(key);
        var cached = entry == null ? null : entry.next();
        if (entry == null || !entry.expiresAt().isAfter(now) || (cached != null && cached.isBefore(now))) {
            if (entries.size() >= MAX_ENTRIES) {
                entries.values().removeIf(e -> !e.expiresAt().isAfter(now));
            }
            entry = new Entry(slots.next(merchantId, durationMin).orElse(null), now.plus(TTL));
            entries.put(key, entry);
        }
        return Optional.ofNullable(entry.next());
    }
}
