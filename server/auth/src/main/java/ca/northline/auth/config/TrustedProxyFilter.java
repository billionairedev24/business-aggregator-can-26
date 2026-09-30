package ca.northline.auth.config;

import ca.northline.auth.application.RequestOrigin;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Client address and {@code X-Forwarded-*} headers, trusted only from the proxies in {@code northline.auth.trusted-proxies}
 * (CIDRs; {@code TRUSTED_PROXIES}). From a trusted peer, {@code X-Forwarded-For} is read right to left and the first
 * address that isn't a trusted proxy is the client (what the rate limits and the sign-in log see);
 * {@code X-Forwarded-Proto/Host/Port} are applied by Spring's {@link ForwardedHeaderFilter}. From anyone else those
 * headers are dropped, so a client can't choose its own IP. Replaces Tomcat's {@code RemoteIpValve}
 * ({@code server.forward-headers-strategy: none}).
 *
 * <p>The client's approximate city (S-19 session list) comes from {@code northline.auth.client-city-header} of a trusted
 * proxy only, and is handed on as the request attribute {@link RequestOrigin#CLIENT_CITY_ATTRIBUTE}.
 */
final class TrustedProxyFilter extends OncePerRequestFilter {

    static final String X_FORWARDED_FOR = "X-Forwarded-For";
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F]*:[0-9a-fA-F:.]*$");

    private static final int MAX_CITY = 100;

    private final List<IpAddressMatcher> trusted;
    private final @Nullable String cityHeader;
    private final ForwardedHeaderFilter apply = new ForwardedHeaderFilter();
    private final ForwardedHeaderFilter strip = new ForwardedHeaderFilter();

    TrustedProxyFilter(List<String> trustedProxies) {
        this(trustedProxies, null);
    }

    TrustedProxyFilter(List<String> trustedProxies, @Nullable String cityHeader) {
        this.cityHeader = cityHeader == null || cityHeader.isBlank() ? null : cityHeader.strip();
        this.trusted = trustedProxies.stream()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(IpAddressMatcher::new)
                .toList();
        strip.setRemoveOnly(true);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var peer = request.getRemoteAddr();
        if (!isTrusted(peer)) {
            strip.doFilter(request, response, chain);
            return;
        }
        var client = clientAddress(peer, Collections.list(request.getHeaders(X_FORWARDED_FOR)));
        var city = city(request);
        if (city != null) {
            request.setAttribute(RequestOrigin.CLIENT_CITY_ATTRIBUTE, city);
        }
        apply.doFilter(
                request,
                response,
                (req, res) -> chain.doFilter(new ClientAddress((HttpServletRequest) req, client), res));
    }

    private @Nullable String city(HttpServletRequest request) {
        var raw = cityHeader == null ? null : request.getHeader(cityHeader);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String city;
        try {
            city = URLDecoder.decode(raw.strip(), StandardCharsets.UTF_8); // CloudFront URL-encodes non-ASCII names
        } catch (IllegalArgumentException e) {
            city = raw.strip();
        }
        city = city.chars().anyMatch(Character::isISOControl) ? "" : city.strip();
        return city.isEmpty() ? null : city.substring(0, Math.min(city.length(), MAX_CITY));
    }

    /** Right to left through the hops while the current one is a trusted proxy. */
    String clientAddress(String peer, List<String> forwardedFor) {
        var hops = new ArrayList<String>();
        forwardedFor.forEach(h -> {
            for (var part : h.split(",")) {
                hops.add(part.strip());
            }
        });
        var candidate = peer;
        for (int i = hops.size() - 1; i >= 0 && isTrusted(candidate); i--) {
            var hop = hops.get(i);
            if (!isIpLiteral(hop)) {
                break; // garbage in the header: keep the last address we could vouch for
            }
            candidate = hop;
        }
        return candidate;
    }

    boolean isTrusted(String address) {
        return isIpLiteral(address) && trusted.stream().anyMatch(m -> m.matches(address));
    }

    /** Only literals: never a host name (no DNS lookups from a header). */
    private static boolean isIpLiteral(String value) {
        return value.length() <= 45
                && (IPV4.matcher(value).matches() || IPV6.matcher(value).matches());
    }

    /** The request as seen from the client. */
    private static final class ClientAddress extends HttpServletRequestWrapper {
        private final String address;

        ClientAddress(HttpServletRequest request, String address) {
            super(request);
            this.address = address;
        }

        @Override
        public String getRemoteAddr() {
            return address;
        }

        @Override
        public String getRemoteHost() {
            return address;
        }
    }
}
