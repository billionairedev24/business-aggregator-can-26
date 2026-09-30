package ca.northline.auth.dpop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ca.northline.auth.support.AuthIntegrationTest.MutableClock;
import ca.northline.auth.support.DpopKey;
import ca.northline.auth.support.SharedValkey;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * S-29: the DPoP state (used proof ids, nonces) in Valkey ({@code valkey/valkey:8}) and in memory — the same behaviour
 * — and what happens when Valkey is unreachable (every token request with DPoP is refused: 503).
 */
class DpopStateTest {

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

        abstract DpopState state();

        @Test
        void aProofId_isUsableOnce_perTtl() {
            var state = state();
            var id = UUID.randomUUID().toString();
            assertThat(state.firstUse(id, Duration.ofMinutes(1))).isTrue();
            assertThat(state.firstUse(id, Duration.ofMinutes(1))).isFalse();
            assertThat(state.firstUse(UUID.randomUUID().toString(), Duration.ofMinutes(1)))
                    .isTrue();
        }

        @Test
        void oneNoncePerWindow_differentAcrossWindows() {
            var state = state();
            var window = Instant.now().toEpochMilli() + UUID.randomUUID().hashCode();
            var nonce = state.nonce(window, Duration.ofMinutes(15));
            assertThat(state.nonce(window, Duration.ofMinutes(15))).isEqualTo(nonce);
            assertThat(state.nonce(window + 1, Duration.ofMinutes(15))).isNotEqualTo(nonce);
            assertThat(nonce).hasSizeGreaterThanOrEqualTo(32);
        }

        @Test
        void nonces_areAcceptedForTheirWindow_andTheNextOne() {
            clock.set(Instant.parse("2026-10-01T12:00:00Z"));
            var nonces = new DpopNonces(state(), clock, Duration.ofMinutes(5));
            var nonce = nonces.current();
            assertThat(nonces.accepts(nonce)).isTrue();
            clock.advanceSeconds(Duration.ofMinutes(5).toSeconds());
            assertThat(nonces.current()).isNotEqualTo(nonce);
            assertThat(nonces.accepts(nonce)).isTrue();
            clock.advanceSeconds(Duration.ofMinutes(5).toSeconds());
            assertThat(nonces.accepts(nonce)).isFalse();
            assertThat(nonces.accepts(null)).isFalse();
            assertThat(nonces.accepts("")).isFalse();
        }
    }

    @Nested
    class InValkey extends Contract {
        @Override
        DpopState state() {
            return new RedisDpopState(new StringRedisTemplate(connections));
        }

        @Test
        void twoInstances_agreeOnTheNonce_andOnUsedProofIds() {
            var one = state();
            var two = state();
            var window = System.nanoTime();
            assertThat(two.nonce(window, Duration.ofMinutes(1))).isEqualTo(one.nonce(window, Duration.ofMinutes(1)));
            var id = UUID.randomUUID().toString();
            assertThat(one.firstUse(id, Duration.ofMinutes(1))).isTrue();
            assertThat(two.firstUse(id, Duration.ofMinutes(1))).isFalse();
            var ttl = new StringRedisTemplate(connections).getExpire(RedisDpopState.PREFIX + "jti:" + id);
            assertThat(ttl).isBetween(1L, 60L);
        }
    }

    @Nested
    class InMemory extends Contract {
        @Override
        DpopState state() {
            return new InMemoryDpopState(clock);
        }

        @Test
        void aProofId_isForgottenAfterItsTtl() {
            var state = state();
            state.firstUse("id", Duration.ofSeconds(70));
            clock.advanceSeconds(71);
            assertThat(state.firstUse("id", Duration.ofSeconds(70))).isTrue();
        }
    }

    @Nested
    class ValkeyUnreachable {

        private final LettuceConnectionFactory nowhere = unreachable();

        private static LettuceConnectionFactory unreachable() {
            var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1));
            factory.afterPropertiesSet();
            factory.start();
            return factory;
        }

        @Test
        void theStoreSaysSo() {
            var state = new RedisDpopState(new StringRedisTemplate(nowhere));
            assertThatThrownBy(() -> state.firstUse("x", Duration.ofMinutes(1)))
                    .isInstanceOf(DpopState.Unavailable.class);
            assertThatThrownBy(() -> state.nonce(1, Duration.ofMinutes(1))).isInstanceOf(DpopState.Unavailable.class);
        }

        @Test
        void aTokenRequestWithAProof_isRefused_503() throws Exception {
            var state = new RedisDpopState(new StringRedisTemplate(nowhere));
            var nonces = new DpopNonces(state, new MutableClock(), Duration.ofMinutes(5));
            var filter = new DpopTokenEndpointFilter(
                    new DpopProofs(state, nonces), nonces, mock(RegisteredClientRepository.class));
            var request = new MockHttpServletRequest("POST", "/oauth2/token");
            request.addHeader("DPoP", DpopKey.generate().proof("POST", "http://localhost/oauth2/token", "n"));
            request.addParameter("grant_type", "refresh_token");
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getHeader("Retry-After")).isEqualTo("30");
            assertThat(response.getContentAsString()).contains("\"error\":\"temporarily_unavailable\"");
            assertThat(chain.getRequest()).isNull();
        }
    }

    @Nested
    class Configuration {

        @Test
        @SuppressWarnings("unchecked")
        void memory_isRefusedUnderStagingAndProd() {
            var env = new MockEnvironment();
            env.setActiveProfiles("prod");
            var props = new DpopProperties(DpopProperties.Store.MEMORY, Duration.ofMinutes(5));
            assertThatThrownBy(() ->
                            new DpopConfig().dpopState(props, mock(ObjectProvider.class), new MutableClock(), env))
                    .hasMessageContaining("not allowed under staging/prod");
            env.setActiveProfiles("local");
            assertThat(new DpopConfig().dpopState(props, mock(ObjectProvider.class), new MutableClock(), env))
                    .isInstanceOf(InMemoryDpopState.class);
        }

        @Test
        @SuppressWarnings("unchecked")
        void redis_isTheDefault() {
            ObjectProvider<RedisConnectionFactory> provider = mock(ObjectProvider.class);
            when(provider.getObject()).thenReturn(connections);
            var props = new DpopProperties(DpopProperties.Store.REDIS, Duration.ofMinutes(5));
            assertThat(new DpopConfig().dpopState(props, provider, new MutableClock(), new MockEnvironment()))
                    .isInstanceOf(RedisDpopState.class);
        }
    }
}
