package ca.northline.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet6Address;
import java.net.InetAddress;
import org.jspecify.annotations.Nullable;

/**
 * Who a request comes from: for search's per-address rate limit (S-44) and the minimised evidence of a CASL consent
 * (S-108). The api sits behind the ingress and the BFFs, so the peer
 * is usually a private address; then the client is the right-most public address of {@code X-Forwarded-For} (each
 * proxy appends the address it saw; entries a client wrote itself are further left and are ignored).
 */
public final class ClientAddress {

    private ClientAddress() {}

    public static String of(HttpServletRequest request) {
        var peer = request.getRemoteAddr();
        var forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank() || !internal(peer)) {
            return peer;
        }
        var hops = forwarded.split(",");
        for (var i = hops.length - 1; i >= 0; i--) {
            var hop = hops[i].strip();
            if (!hop.isEmpty() && !internal(hop)) {
                return hop;
            }
        }
        return peer;
    }

    /** Loopback, link-local, RFC 1918 / RFC 6598 or IPv6 unique-local; anything unparseable counts as external. */
    static boolean internal(@Nullable String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        try {
            var ip = InetAddress.ofLiteral(address.strip());
            if (ip.isLoopbackAddress() || ip.isLinkLocalAddress() || ip.isSiteLocalAddress()) {
                return true;
            }
            var bytes = ip.getAddress();
            if (ip instanceof Inet6Address) {
                return (bytes[0] & 0xfe) == 0xfc; // fc00::/7
            }
            return (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64; // 100.64.0.0/10
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
