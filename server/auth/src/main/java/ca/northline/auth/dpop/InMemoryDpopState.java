package ca.northline.auth.dpop;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The same state in this JVM only — local runs without Valkey and tests; refused under staging/prod. */
final class InMemoryDpopState implements DpopState {

    private static final int SWEEP_ABOVE = 10_000;

    private final Clock clock;
    private final Map<String, Instant> used = new ConcurrentHashMap<>();
    private final Map<Long, String> nonces = new ConcurrentHashMap<>();

    InMemoryDpopState(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean firstUse(String key, Duration ttl) {
        var now = clock.instant();
        if (used.size() > SWEEP_ABOVE) {
            used.values().removeIf(until -> until.isBefore(now));
        }
        var fresh = new boolean[1];
        used.compute(key, (_, until) -> {
            fresh[0] = until == null || until.isBefore(now);
            return fresh[0] ? now.plus(ttl) : until;
        });
        return fresh[0];
    }

    @Override
    public String nonce(long window, Duration ttl) {
        nonces.keySet().removeIf(w -> w < window - 2);
        return nonces.computeIfAbsent(window, _ -> RedisDpopState.randomNonce());
    }
}
