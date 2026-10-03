package ca.northline.bff.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * S-117: one token refresh at a time per browser session. northline-auth rotates refresh tokens (each is single use), and
 * a Studio screen sends several {@code /api} calls at once: when the 10-minute access token had just expired, each of
 * them refreshed with the same refresh token, all but the first got {@code invalid_grant}, and {@link
 * SessionRevocationCheck} ended the session ("refresh refused") — the person was signed out mid-screen. Found by the
 * end-to-end suite.
 *
 * <p>Requests of one session queue behind a lock (striped by session id, nothing to clean up). A request that waited
 * may still hold the session as it was when it started (Spring Session loads it once per request), so the token pair
 * each refresh replaced is remembered for {@link #REMEMBER}: a request presenting a refresh token that was just rotated
 * gets the new client, which is also stored in its own session copy, instead of refreshing again. The lock is per
 * replica; two replicas refreshing one session in the same instant can still race (the ingress keeps a session's calls
 * together most of the time).
 */
final class SerializedRefresh implements OAuth2AuthorizedClientManager {

    static final Duration REMEMBER = Duration.ofMinutes(2);
    private static final int STRIPES = 256;

    private record Rotated(OAuth2AuthorizedClient replacement, Instant until) {}

    private final OAuth2AuthorizedClientManager delegate;
    private final OAuth2AuthorizedClientRepository clients;
    private final Clock clock;
    private final ReentrantLock[] locks =
            IntStream.range(0, STRIPES).mapToObj(_ -> new ReentrantLock()).toArray(ReentrantLock[]::new);
    private final Map<String, Rotated> rotated = new ConcurrentHashMap<>();

    SerializedRefresh(OAuth2AuthorizedClientManager delegate, OAuth2AuthorizedClientRepository clients, Clock clock) {
        this.delegate = delegate;
        this.clients = clients;
        this.clock = clock;
    }

    @Override
    public @Nullable OAuth2AuthorizedClient authorize(OAuth2AuthorizeRequest request) {
        var servlet = servletRequest(request);
        var session = servlet == null ? null : servlet.getSession(false);
        if (servlet == null || session == null) {
            return delegate.authorize(request);
        }
        var lock = locks[Math.floorMod(session.getId().hashCode(), STRIPES)];
        lock.lock();
        try {
            var now = clock.instant();
            rotated.values().removeIf(r -> r.until().isBefore(now));
            var current =
                    clients.loadAuthorizedClient(request.getClientRegistrationId(), request.getPrincipal(), servlet);
            var before = refreshToken(current);
            var known = before == null ? null : rotated.get(before);
            if (known != null) {
                var response = response();
                if (response != null) {
                    clients.saveAuthorizedClient(known.replacement(), request.getPrincipal(), servlet, response);
                }
                return known.replacement();
            }
            var result = delegate.authorize(request);
            var after = refreshToken(result);
            if (before != null && result != null && after != null && !after.equals(before)) {
                rotated.put(before, new Rotated(result, now.plus(REMEMBER)));
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    private static @Nullable String refreshToken(@Nullable OAuth2AuthorizedClient client) {
        return client == null || client.getRefreshToken() == null
                ? null
                : client.getRefreshToken().getTokenValue();
    }

    /** The request attribute, else the current request — where the delegate finds it too. */
    private static @Nullable HttpServletRequest servletRequest(OAuth2AuthorizeRequest request) {
        if (request.getAttribute(HttpServletRequest.class.getName()) instanceof HttpServletRequest r) {
            return r;
        }
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a
                ? a.getRequest()
                : null;
    }

    private static @Nullable HttpServletResponse response() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a
                ? a.getResponse()
                : null;
    }
}
