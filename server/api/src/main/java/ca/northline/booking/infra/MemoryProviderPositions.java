package ca.northline.booking.infra;

import ca.northline.booking.application.ProviderPositions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;

/** {@link ProviderPositions} in one process ({@code northline.live.bus=memory}: local, test), on the app clock. */
@RequiredArgsConstructor
class MemoryProviderPositions implements ProviderPositions {

    private record Kept(Position position, Instant until) {}

    private final Clock clock;
    private final Map<String, Instant> nextPing = new ConcurrentHashMap<>();
    private final Map<String, Kept> positions = new ConcurrentHashMap<>();

    @Override
    public boolean allow(String bookingId, Duration interval) {
        var now = clock.instant();
        var allowed = new boolean[1];
        nextPing.compute(bookingId, (_, next) -> {
            if (next != null && now.isBefore(next)) {
                return next;
            }
            allowed[0] = true;
            return now.plus(interval);
        });
        return allowed[0];
    }

    @Override
    public void put(String bookingId, Position position, Duration ttl) {
        positions.put(bookingId, new Kept(position, clock.instant().plus(ttl)));
    }

    @Override
    public Optional<Position> latest(String bookingId) {
        var kept = positions.get(bookingId);
        if (kept == null || clock.instant().isAfter(kept.until())) {
            return Optional.empty();
        }
        return Optional.of(kept.position());
    }

    @Override
    public void clear(String bookingId) {
        positions.remove(bookingId);
        nextPing.remove(bookingId);
    }
}
