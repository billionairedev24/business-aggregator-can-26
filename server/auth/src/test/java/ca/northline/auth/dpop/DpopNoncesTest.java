package ca.northline.auth.dpop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ca.northline.auth.replay.ReplayStore;
import ca.northline.auth.support.AuthIntegrationTest.MutableClock;
import ca.northline.auth.support.DpopKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/** S-29: nonce windows, and a token request with a DPoP proof while the replay store is down (503). */
class DpopNoncesTest {

    /** Shared values in a map (the store's own behaviour is ReplayStoreTest's). */
    private static final class MapStore implements ReplayStore {
        private final Map<String, String> values = new ConcurrentHashMap<>();

        @Override
        public boolean firstUse(String key, Duration ttl) {
            return values.putIfAbsent(key, "1") == null;
        }

        @Override
        public String shared(String key, Duration ttl) {
            return values.computeIfAbsent(key, _ -> UUID.randomUUID().toString());
        }
    }

    /** Valkey unreachable. */
    private static final class DownStore implements ReplayStore {
        @Override
        public boolean firstUse(String key, Duration ttl) {
            throw new Unavailable(new QueryTimeoutException("down"));
        }

        @Override
        public String shared(String key, Duration ttl) {
            throw new Unavailable(new QueryTimeoutException("down"));
        }
    }

    @Test
    void aNonce_isAcceptedForItsWindow_andTheNextOne() {
        var clock = new MutableClock();
        clock.set(Instant.parse("2026-10-01T12:00:00Z"));
        var nonces = new DpopNonces(new MapStore(), clock, Duration.ofMinutes(5));
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

    @Test
    void aTokenRequestWithAProof_whileTheStoreIsDown_is503() throws Exception {
        var down = new DownStore();
        var nonces = new DpopNonces(down, new MutableClock(), Duration.ofMinutes(5));
        var filter = new DpopTokenEndpointFilter(
                new DpopProofs(down, nonces), nonces, mock(RegisteredClientRepository.class));
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
