package ca.northline.bff.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * S-117: parallel relayed calls of one session whose access token expired refresh once; the others get the new token
 * instead of presenting the rotated refresh token again ({@code invalid_grant}, which ended the session).
 */
class SerializedRefreshTest {

    static final int CALLS = 8;
    static final ClientRegistration STUDIO = ClientRegistration.withRegistrationId("studio")
            .clientId("studio-bff")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .authorizationUri("http://auth/oauth2/authorize")
            .tokenUri("http://auth/oauth2/token")
            .build();

    final Authentication principal = new OAuth2AuthenticationToken(
            new DefaultOAuth2User(List.of(), Map.of("sub", "u1"), "sub"), List.of(), "studio");
    final HttpSessionOAuth2AuthorizedClientRepository repository = new HttpSessionOAuth2AuthorizedClientRepository();

    /** northline-auth's token endpoint: rotating refresh tokens, a used one is refused. */
    final Set<String> spent = ConcurrentHashMap.newKeySet();

    final AtomicInteger refreshes = new AtomicInteger();
    final AtomicInteger generation = new AtomicInteger();

    static OAuth2AuthorizedClient client(String access, String refresh, boolean expired) {
        var now = Instant.now();
        var issued = expired ? now.minusSeconds(900) : now;
        return new OAuth2AuthorizedClient(
                STUDIO,
                "u1",
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, access, issued, issued.plusSeconds(600)),
                new OAuth2RefreshToken(refresh, issued));
    }

    /** What DefaultOAuth2AuthorizedClientManager does: load from the session, refresh an expired token, store. */
    final OAuth2AuthorizedClientManager defaultManager = request -> {
        var attributes = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        var current = repository.loadAuthorizedClient("studio", request.getPrincipal(), attributes.getRequest());
        if (current == null || current.getAccessToken().getExpiresAt().isAfter(Instant.now())) {
            return current;
        }
        var presented = current.getRefreshToken().getTokenValue();
        sleep(); // the token endpoint takes a moment: the other calls arrive meanwhile
        if (!spent.add(presented)) {
            throw new ClientAuthorizationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT), "studio");
        }
        refreshes.incrementAndGet();
        var n = generation.incrementAndGet();
        var fresh = client("a" + n, "r" + n, false);
        repository.saveAuthorizedClient(
                fresh, request.getPrincipal(), attributes.getRequest(), attributes.getResponse());
        return fresh;
    };

    static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * CALLS parallel calls; {@code sessionOf} gives each call its session — the same object (in-memory sessions) or its
     * own copy with the same id (Spring Session loads one per request). Returns the access token each call relayed, or
     * the OAuth error code it failed with.
     */
    List<String> parallelCalls(OAuth2AuthorizedClientManager manager, Function<Integer, MockHttpSession> sessionOf)
            throws Exception {
        var start = new CountDownLatch(1);
        var results = new ArrayList<Future<String>>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CALLS; i++) {
                var session = sessionOf.apply(i);
                results.add(pool.submit(() -> {
                    var request = new MockHttpServletRequest("GET", "/api/v1/me");
                    request.setSession(session);
                    RequestContextHolder.setRequestAttributes(
                            new ServletRequestAttributes(request, new MockHttpServletResponse()));
                    try {
                        start.await();
                        var client = manager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId("studio")
                                .principal(principal)
                                .build());
                        return client == null ? "none" : client.getAccessToken().getTokenValue();
                    } catch (ClientAuthorizationException e) {
                        return e.getError().getErrorCode();
                    } finally {
                        RequestContextHolder.resetRequestAttributes();
                    }
                }));
            }
            start.countDown();
            var out = new ArrayList<String>();
            for (var f : results) {
                out.add(f.get());
            }
            return out;
        }
    }

    MockHttpSession sessionHolding(OAuth2AuthorizedClient client, @Nullable MockHttpSession like) {
        var session = like == null ? new MockHttpSession() : new MockHttpSession(null, like.getId());
        var request = new MockHttpServletRequest();
        request.setSession(session);
        repository.saveAuthorizedClient(client, principal, request, new MockHttpServletResponse());
        return session;
    }

    @Test
    void withoutSerializing_theParallelRefreshesAreRefused() throws Exception {
        var session = sessionHolding(client("a0", "r0", true), null);
        assertThat(parallelCalls(defaultManager, _ -> session)).contains(OAuth2ErrorCodes.INVALID_GRANT);
    }

    @Test
    void oneSessionObject_refreshesOnce_andEveryCallRelaysTheNewToken() throws Exception {
        var manager = new SerializedRefresh(defaultManager, repository, Clock.systemUTC());
        var session = sessionHolding(client("a0", "r0", true), null);
        assertThat(parallelCalls(manager, _ -> session)).hasSize(CALLS).containsOnly("a1");
        assertThat(refreshes).hasValue(1);
    }

    @Test
    void aSessionCopyPerRequest_stillRefreshesOnce() throws Exception {
        var manager = new SerializedRefresh(defaultManager, repository, Clock.systemUTC());
        var first = sessionHolding(client("a0", "r0", true), null);
        // Valkey sessions: every request read the session before the first refresh stored the new pair
        assertThat(parallelCalls(manager, i -> i == 0 ? first : sessionHolding(client("a0", "r0", true), first)))
                .hasSize(CALLS)
                .containsOnly("a1");
        assertThat(refreshes).hasValue(1);
    }
}
