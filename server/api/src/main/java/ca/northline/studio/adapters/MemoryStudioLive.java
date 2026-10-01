package ca.northline.studio.adapters;

import ca.northline.studio.application.StudioLive;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** {@link StudioLive} inside one process ({@code northline.live.bus=memory}: local and test — one api, no Valkey). */
class MemoryStudioLive implements StudioLive {

    private final Listeners listeners = new Listeners();

    @Override
    public void signal(String merchantId, Signal signal) {
        listeners.deliver(merchantId, signal);
    }

    @Override
    public Subscription subscribe(String merchantId, Consumer<Signal> listener) {
        return listeners.add(merchantId, listener);
    }

    /** The open streams of this replica, by merchant. Shared by both adapters. */
    static final class Listeners {

        private final Map<String, Set<Consumer<Signal>>> byMerchant = new ConcurrentHashMap<>();

        void deliver(String merchantId, Signal signal) {
            byMerchant.getOrDefault(merchantId, Set.of()).forEach(l -> l.accept(signal));
        }

        Subscription add(String merchantId, Consumer<Signal> listener) {
            byMerchant
                    .computeIfAbsent(merchantId, _ -> ConcurrentHashMap.newKeySet())
                    .add(listener);
            return () -> byMerchant.computeIfPresent(merchantId, (_, set) -> {
                set.remove(listener);
                return set.isEmpty() ? null : set;
            });
        }
    }
}
