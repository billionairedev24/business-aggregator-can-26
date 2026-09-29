package ca.northline.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Client IP and forwarded headers: believed only from the configured proxies (TRUSTED_PROXIES). */
class TrustedProxyFilterTest {

    private final TrustedProxyFilter filter = new TrustedProxyFilter(List.of("10.0.0.0/8", "fd00::/8"));

    private record Seen(String remoteAddr, String scheme, String host) {}

    private Seen through(String peer, String... headers) throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/auth/sign-in");
        request.setRemoteAddr(peer);
        for (int i = 0; i < headers.length; i += 2) {
            request.addHeader(headers[i], headers[i + 1]);
        }
        var seen = new AtomicReference<Seen>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, _) -> {
            var r = (jakarta.servlet.http.HttpServletRequest) req;
            seen.set(new Seen(r.getRemoteAddr(), r.getScheme(), r.getServerName()));
        });
        return seen.get();
    }

    @Test
    void trustedProxy_forwardedForAndProtoApply() throws Exception {
        var seen = through(
                "10.0.3.7",
                "X-Forwarded-For",
                "203.0.113.9",
                "X-Forwarded-Proto",
                "https",
                "X-Forwarded-Host",
                "auth.northline.ca");
        assertThat(seen).isEqualTo(new Seen("203.0.113.9", "https", "auth.northline.ca"));
    }

    @Test
    void untrustedPeer_forwardedHeadersAreDropped() throws Exception {
        var seen = through(
                "192.168.1.20", // private, but not configured as a proxy
                "X-Forwarded-For",
                "203.0.113.9",
                "X-Forwarded-Proto",
                "https",
                "X-Forwarded-Host",
                "evil.example");
        assertThat(seen).isEqualTo(new Seen("192.168.1.20", "http", "localhost"));
    }

    @Test
    void chainOfProxies_rightMostUntrustedIsTheClient() {
        assertThat(filter.clientAddress("10.0.0.1", List.of("198.51.100.1, 203.0.113.9, 10.2.2.2")))
                .isEqualTo("203.0.113.9");
        assertThat(filter.clientAddress("10.0.0.1", List.of("198.51.100.1", "10.2.2.2"))) // two header lines
                .isEqualTo("198.51.100.1");
        assertThat(filter.clientAddress("10.0.0.1", List.of("10.3.3.3, 10.2.2.2")))
                .isEqualTo("10.3.3.3");
        assertThat(filter.clientAddress("fd00::1", List.of("2001:db8::7"))).isEqualTo("2001:db8::7");
    }

    @Test
    void garbageOrHostNames_areNeverTrusted() {
        assertThat(filter.clientAddress("10.0.0.1", List.of("unknown, 10.2.2.2")))
                .isEqualTo("10.2.2.2");
        assertThat(filter.clientAddress("10.0.0.1", List.of("cafe"))).isEqualTo("10.0.0.1");
        assertThat(filter.isTrusted("localhost")).isFalse();
    }

    @Test
    void noProxiesConfigured_meansNoForwardedHeadersAtAll() throws Exception {
        var none = new TrustedProxyFilter(List.of());
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.9");
        var seen = new AtomicReference<String>();
        none.doFilter(request, new MockHttpServletResponse(), (req, _) -> seen.set(req.getRemoteAddr()));
        assertThat(seen.get()).isEqualTo("10.0.0.1");
    }
}
