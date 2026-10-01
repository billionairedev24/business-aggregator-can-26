package ca.northline.orders.persistence;

import ca.northline.orders.application.TrackingBus;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** {@link TrackingBus} inside one process — {@code local} and {@code test}, where there is one api and no Redis. */
@Component
@Profile({"local", "test"})
class MemoryTrackingBus implements TrackingBus {

    private final Map<String, Set<Runnable>> listeners = new ConcurrentHashMap<>();

    @Override
    public void changed(String orderId) {
        listeners.getOrDefault(orderId, Set.of()).forEach(Runnable::run);
    }

    @Override
    public Subscription subscribe(String orderId, Runnable onChange) {
        listeners.computeIfAbsent(orderId, _ -> ConcurrentHashMap.newKeySet()).add(onChange);
        return () -> listeners.computeIfPresent(orderId, (_, set) -> {
            set.remove(onChange);
            return set.isEmpty() ? null : set;
        });
    }
}
