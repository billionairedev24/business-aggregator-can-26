package ca.northline.config;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.DPoPProofContext;
import org.springframework.security.oauth2.jwt.DPoPProofJwtDecoderFactory;
import org.springframework.security.oauth2.jwt.DPoPProofReplayValidator;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;

/**
 * S-29: how the api checks DPoP proofs on requests with a DPoP-bound access token ({@code Authorization: DPoP …}).
 * Spring Security's {@link DPoPProofJwtDecoderFactory} does the proof ({@code typ}, key, signature, {@code htm},
 * {@code htu}, {@code iat} ± 30 s, {@code ath} = this token, the key's thumbprint = the token's {@code cnf.jkt}); its
 * replay check needs a cache shared by every api instance: Valkey ({@code nl:api-dpop:jti:<sha256>}, SET NX with a
 * TTL) in the cloud profiles, Spring's in-memory cache under {@code local} / {@code test} (like the other Redis stores).
 * No nonce is asked for here: the auth server's nonce, {@code iat} and the single-use {@code jti} already bound a
 * captured proof to one request within seconds.
 */
@Configuration(proxyBeanMethods = false)
class DpopResourceConfig {

    @Bean
    JwtDecoderFactory<DPoPProofContext> dpopProofDecoders(Cache dpopProofIds) {
        var replay = new DPoPProofReplayValidator(dpopProofIds);
        var decoders = new DPoPProofJwtDecoderFactory();
        decoders.setJwtValidatorFactory(DPoPProofJwtDecoderFactory.createDefaultJwtValidatorFactory(List.of(replay)));
        return decoders;
    }

    @Bean
    @Profile("local | test")
    Cache dpopProofIdsInMemory() {
        return new DPoPProofReplayValidator.InMemoryCache();
    }

    @Bean
    @Profile("!local & !test")
    Cache dpopProofIdsInValkey(StringRedisTemplate redis, Clock clock) {
        return new ValkeyProofIds(redis, clock);
    }

    /** Used proof ids in Valkey; only {@code putIfAbsent} is used by the replay check. */
    @RequiredArgsConstructor
    static final class ValkeyProofIds implements Cache {

        private static final Duration MARGIN = Duration.ofSeconds(10);
        private static final SimpleValueWrapper SEEN = new SimpleValueWrapper(true);

        private final StringRedisTemplate redis;
        private final Clock clock;

        @Override
        public @Nullable ValueWrapper putIfAbsent(Object key, @Nullable Object value) {
            var ttl = MARGIN;
            if (value instanceof DPoPProofReplayValidator.CacheValue proof) {
                var left = Duration.between(clock.instant(), proof.getExpiresAt());
                ttl = left.isNegative() ? MARGIN : left.plus(MARGIN);
            }
            var first = redis.opsForValue().setIfAbsent("nl:api-dpop:jti:" + key, "1", ttl);
            return Boolean.TRUE.equals(first) ? null : SEEN;
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
            return redis;
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
}
