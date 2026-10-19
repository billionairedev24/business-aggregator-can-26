package ca.northline.auth.config;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The {@code local} profile only ({@code northline.auth.local-network-devices: true} in application-local.yml): a
 * phone, simulator or emulator signs in to the native apps against the developer's machine
 * (docs/runbooks/mobile-local.md). A phone on the same Wi-Fi reaches the machine by its LAN address
 * ({@code 192.168.1.20}), an Android emulator by {@code 10.0.2.2}; the clients are registered on {@code localhost}.
 * So, when on:
 *
 * <ul>
 *   <li>a redirect URI registered on a loopback host ({@code localhost}, {@code 127.0.0.1}) also matches the same
 *       scheme, port, path and query on a private-network host (RFC 1918, link-local, {@code *.local}) — the consumer
 *       app's in-app sign-in lands on {@code http://<host>:3000/app/oauth2redirect};
 *   <li>the sign-in page an authorization request is sent to keeps the host the request came in on, when the page is
 *       configured on a loopback host and the request came from a private-network host.
 * </ul>
 *
 * <p>Likewise a browser origin that is the network variant of an allowed loopback origin (the consumer site's sign-in
 * page on {@code http://<host>:3000}, which the courier app's system-browser sign-in opens) passes the JSON API's
 * CORS and Origin checks.
 *
 * <p>Off by default and in every other profile: dev, staging and prod match redirect URIs exactly (RFC 9700 § 4.1.3)
 * and the setting is never set there.
 */
@Component
public class LocalNetworkDevices {

    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");

    private final boolean enabled;

    LocalNetworkDevices(@Value("${northline.auth.local-network-devices:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /** A host on the developer's own network, never loopback: RFC 1918 and link-local IPv4 literals, mDNS names. */
    static boolean isLocalNetworkHost(@Nullable String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        var h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".local") && h.length() > ".local".length()) {
            return true;
        }
        var m = IPV4.matcher(h);
        if (!m.matches()) {
            return false;
        }
        int[] o = new int[4];
        for (int i = 0; i < 4; i++) {
            o[i] = Integer.parseInt(m.group(i + 1));
            if (o[i] > 255) {
                return false;
            }
        }
        return o[0] == 10
                || (o[0] == 172 && o[1] >= 16 && o[1] <= 31)
                || (o[0] == 192 && o[1] == 168)
                || (o[0] == 169 && o[1] == 254);
    }

    static boolean isLoopback(@Nullable String host) {
        if (host == null) {
            return false;
        }
        var h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("[::1]") || h.equals("::1") || (IPV4.matcher(h).matches() && h.startsWith("127."));
    }

    /** The loopback redirect URI of {@code client} that {@code requested} is the network variant of; null if none. */
    @Nullable String registeredVariant(RegisteredClient client, @Nullable String requested) {
        var wanted = enabled ? uri(requested) : null;
        if (wanted == null || !isLocalNetworkHost(wanted.getHost())) {
            return null;
        }
        for (var registered : client.getRedirectUris()) {
            var r = uri(registered);
            if (r != null
                    && isLoopback(r.getHost())
                    && Objects.equals(r.getScheme(), wanted.getScheme())
                    && r.getPort() == wanted.getPort()
                    && Objects.equals(r.getRawPath(), wanted.getRawPath())
                    && Objects.equals(r.getRawQuery(), wanted.getRawQuery())
                    && wanted.getRawFragment() == null
                    && wanted.getRawUserInfo() == null) {
                return registered;
            }
        }
        return null;
    }

    /**
     * Wraps the authorization request validator: a network variant of a registered loopback redirect is validated as
     * if it were registered too (every other check — scope, PKCE, prompt — runs unchanged).
     */
    Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> redirects(
            Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> validator) {
        if (!enabled) {
            return validator;
        }
        return context -> {
            var client = context.getRegisteredClient();
            OAuth2AuthorizationCodeRequestAuthenticationToken authentication = context.getAuthentication();
            var requested = authentication.getRedirectUri();
            if (requested == null
                    || client.getRedirectUris().contains(requested)
                    || registeredVariant(client, requested) == null) {
                validator.accept(context);
                return;
            }
            var widened = RegisteredClient.from(client).redirectUri(requested).build();
            var builder = OAuth2AuthorizationCodeRequestAuthenticationContext.with(authentication)
                    .registeredClient(widened);
            if (context.getAuthorizationRequest() != null) {
                builder.authorizationRequest(context.getAuthorizationRequest());
            }
            if (context.getAuthorizationConsent() != null) {
                builder.authorizationConsent(context.getAuthorizationConsent());
            }
            validator.accept(builder.build());
        };
    }

    /** Whether {@code origin} is the network variant (same scheme and port) of one of the allowed loopback origins. */
    boolean allowsOrigin(@Nullable String origin, Collection<String> allowed) {
        var o = enabled ? uri(origin) : null;
        if (o == null || !isLocalNetworkHost(o.getHost()) || (o.getRawPath() != null && !o.getRawPath().isEmpty())) {
            return false;
        }
        return allowed.stream().map(LocalNetworkDevices::uri).anyMatch(a -> a != null
                && isLoopback(a.getHost())
                && Objects.equals(a.getScheme(), o.getScheme())
                && a.getPort() == o.getPort());
    }

    /** The sign-in page for a request: a loopback page keeps the private-network host the request came in on. */
    String loginPage(String page, HttpServletRequest request) {
        var host = request.getServerName();
        var configured = enabled ? uri(page) : null;
        if (configured == null || !isLoopback(configured.getHost()) || !isLocalNetworkHost(host)) {
            return page;
        }
        return UriComponentsBuilder.fromUriString(page).host(host).toUriString();
    }

    private static @Nullable URI uri(@Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
