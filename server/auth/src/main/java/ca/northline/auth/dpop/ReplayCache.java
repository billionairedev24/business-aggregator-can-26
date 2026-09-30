package ca.northline.auth.dpop;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.security.oauth2.jwt.DPoPProofReplayValidator;

/**
 * The cache Spring Security's {@link DPoPProofReplayValidator} records used proof ids in, over {@link DpopState}
 * (Valkey): only {@link #putIfAbsent} matters — a non-null answer means "seen before", and the validator refuses the
 * proof. Entries live until the proof could no longer pass the {@code iat} check anyway.
 */
final class ReplayCache implements Cache {

    private static final SimpleValueWrapper SEEN = new SimpleValueWrapper(true);
    private static final Duration MARGIN = Duration.ofSeconds(10);

    private final DpopState state;
    private final Clock clock;

    ReplayCache(DpopState state, Clock clock) {
        this.state = state;
        this.clock = clock;
    }

    @Override
    public @Nullable ValueWrapper putIfAbsent(Object key, @Nullable Object value) {
        var ttl = MARGIN;
        if (value instanceof DPoPProofReplayValidator.CacheValue proof) {
            var left = Duration.between(clock.instant(), proof.getExpiresAt());
            ttl = left.isNegative() ? MARGIN : left.plus(MARGIN);
        }
        return state.firstUse(String.valueOf(key), ttl) ? null : SEEN;
    }

    @Override
    public void put(Object key, @Nullable Object value) {
        putIfAbsent(key, value);
    }

    @Override
    public String getName() {
        return "dpop-proof-ids";
    }

    @Override
    public Object getNativeCache() {
        return state;
    }

    @Override
    public @Nullable ValueWrapper get(Object key) {
        return null;
    }

    @Override
    public <T> @Nullable T get(Object key, @Nullable Class<T> type) {
        return null;
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        throw new UnsupportedOperationException("proof ids are only recorded");
    }

    @Override
    public void evict(Object key) {
        // A used proof id stays used until it expires.
    }

    @Override
    public void clear() {
        // Never cleared: that would re-open every recent proof to replay.
    }
}
