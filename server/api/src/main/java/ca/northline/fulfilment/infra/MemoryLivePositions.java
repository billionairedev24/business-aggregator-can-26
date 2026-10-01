package ca.northline.fulfilment.infra;

import ca.northline.fulfilment.api.CourierLocations.Position;
import ca.northline.fulfilment.api.CourierLocations.Subscription;
import ca.northline.fulfilment.application.LivePositions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** {@link LivePositions} in one process ({@code northline.live.bus=memory}: local, test), on the application clock. */
class MemoryLivePositions implements LivePositions {

    private record Kept(Position position, Instant until) {}

    private final Clock clock;
    private final Map<String, Instant> nextPing = new ConcurrentHashMap<>();
    private final Map<String, Kept> positions = new ConcurrentHashMap<>();
    private final Map<String, Set<Runnable>> listeners = new ConcurrentHashMap<>();

    MemoryLivePositions(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean allow(String courierId, Duration interval) {
        var now = clock.instant();
        var allowed = new boolean[1];
        nextPing.compute(courierId, (_, next) -> {
            if (next != null && now.isBefore(next)) {
                return next;
            }
            allowed[0] = true;
            return now.plus(interval);
        });
        return allowed[0];
    }

    @Override
    public void put(String courierId, Position position, Duration ttl) {
        positions.put(courierId, new Kept(position, clock.instant().plus(ttl)));
    }

    @Override
    public Optional<Position> latest(String courierId) {
        var kept = positions.get(courierId);
        if (kept == null || clock.instant().isAfter(kept.until())) {
            return Optional.empty();
        }
        return Optional.of(kept.position());
    }

    @Override
    public void moved(String orderId) {
        listeners.getOrDefault(orderId, Set.of()).forEach(Runnable::run);
    }

    @Override
    public Subscription subscribe(String orderId, Runnable onMove) {
        listeners.computeIfAbsent(orderId, _ -> ConcurrentHashMap.newKeySet()).add(onMove);
        return () -> listeners.computeIfPresent(orderId, (_, set) -> {
            set.remove(onMove);
            return set.isEmpty() ? null : set;
        });
    }
}
