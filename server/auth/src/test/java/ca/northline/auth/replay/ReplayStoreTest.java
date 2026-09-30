package ca.northline.auth.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ca.northline.auth.support.AuthIntegrationTest.MutableClock;
import ca.northline.auth.support.SharedValkey;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;

/**
 * S-29: one-time ids and shared values in Valkey ({@code valkey/valkey:8}) and in memory — the same behaviour — and
 * what an unreachable Valkey looks like to callers.
 */
class ReplayStoreTest {

    static LettuceConnectionFactory connections;

    @BeforeAll
    static void connect() {
        connections = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(SharedValkey.host(), SharedValkey.port()));
        connections.afterPropertiesSet();
        connections.start();
    }

    @AfterAll
    static void disconnect() {
        connections.destroy();
    }

    abstract static class Contract {
        final MutableClock clock = new MutableClock();

        abstract ReplayStore store();

        @Test
        void anId_isUsableOnce_perTtl() {
            var store = store();
            var id = UUID.randomUUID().toString();
            assertThat(store.firstUse(id, Duration.ofMinutes(1))).isTrue();
            assertThat(store.firstUse(id, Duration.ofMinutes(1))).isFalse();
            assertThat(store.firstUse(UUID.randomUUID().toString(), Duration.ofMinutes(1)))
                    .isTrue();
        }

        @Test
        void aSharedValue_isStable_perKey_andRandom() {
            var store = store();
            var key = "k:" + UUID.randomUUID();
            var value = store.shared(key, Duration.ofMinutes(15));
            assertThat(store.shared(key, Duration.ofMinutes(15))).isEqualTo(value);
            assertThat(store.shared(key + "x", Duration.ofMinutes(15))).isNotEqualTo(value);
            assertThat(value).hasSizeGreaterThanOrEqualTo(32);
        }
    }

    @Nested
    class InValkey extends Contract {
        @Override
        ReplayStore store() {
            return new RedisReplayStore(new StringRedisTemplate(connections));
        }

        @Test
        void twoInstances_agreeOnSharedValues_andOnUsedIds() {
            var one = store();
            var two = store();
            var key = "k:" + UUID.randomUUID();
            assertThat(two.shared(key, Duration.ofMinutes(1))).isEqualTo(one.shared(key, Duration.ofMinutes(1)));
            var id = UUID.randomUUID().toString();
            assertThat(one.firstUse(id, Duration.ofMinutes(1))).isTrue();
            assertThat(two.firstUse(id, Duration.ofMinutes(1))).isFalse();
            var ttl = new StringRedisTemplate(connections).getExpire(RedisReplayStore.PREFIX + id);
            assertThat(ttl).isBetween(1L, 60L);
        }
    }

    @Nested
    class InMemory extends Contract {
        @Override
        ReplayStore store() {
            return new InMemoryReplayStore(clock);
        }

        @Test
        void idsAndValues_areForgottenAfterTheirTtl() {
            var store = store();
            store.firstUse("id", Duration.ofSeconds(70));
            var value = store.shared("v", Duration.ofSeconds(70));
            clock.advanceSeconds(71);
            assertThat(store.firstUse("id", Duration.ofSeconds(70))).isTrue();
            assertThat(store.shared("v", Duration.ofSeconds(70))).isNotEqualTo(value);
        }
    }

    @Test
    void valkeyUnreachable_isReportedAsUnavailable() {
        var nowhere = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1));
        nowhere.afterPropertiesSet();
        nowhere.start();
        var store = new RedisReplayStore(new StringRedisTemplate(nowhere));
        assertThatThrownBy(() -> store.firstUse("x", Duration.ofMinutes(1)))
                .isInstanceOf(ReplayStore.Unavailable.class);
        assertThatThrownBy(() -> store.shared("x", Duration.ofMinutes(1))).isInstanceOf(ReplayStore.Unavailable.class);
        nowhere.destroy();
    }

    @Nested
    class Configuration {

        @Test
        @SuppressWarnings("unchecked")
        void memory_isRefusedUnderStagingAndProd() {
            var env = new MockEnvironment();
            env.setActiveProfiles("prod");
            var props = new ReplayStoreConfig.Properties(ReplayStoreConfig.Store.MEMORY);
            assertThatThrownBy(() -> new ReplayStoreConfig()
                            .replayStore(props, mock(ObjectProvider.class), new MutableClock(), env))
                    .hasMessageContaining("not allowed under staging/prod");
            env.setActiveProfiles("local");
            assertThat(new ReplayStoreConfig().replayStore(props, mock(ObjectProvider.class), new MutableClock(), env))
                    .isInstanceOf(InMemoryReplayStore.class);
        }

        @Test
        @SuppressWarnings("unchecked")
        void redis_isTheDefault() {
            ObjectProvider<RedisConnectionFactory> provider = mock(ObjectProvider.class);
            when(provider.getObject()).thenReturn(connections);
            var props = new ReplayStoreConfig.Properties(ReplayStoreConfig.Store.REDIS);
            assertThat(new ReplayStoreConfig().replayStore(props, provider, new MutableClock(), new MockEnvironment()))
                    .isInstanceOf(RedisReplayStore.class);
        }
    }
}
