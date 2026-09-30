package ca.northline.worker.webhooks;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Where webhook deliveries may go (S-33, SSRF): https only, to public unicast addresses. Refused — whatever DNS says —
 * loopback, "this network", private (RFC 1918, IPv6 ULA), carrier-grade NAT, link-local (which holds the cloud
 * metadata services 169.254.169.254 / 169.254.170.2 / fd00:ec2::254), multicast, broadcast, documentation and
 * benchmarking ranges, and IPv4 embedded in IPv6 (mapped, NAT64, 6to4) when the embedded address is refused. With
 * {@code allowLocal} (local development and tests only) loopback and {@code http} are allowed, nothing else.
 *
 * <p>The check runs on the resolved addresses, and {@link HttpWebhookTransport} connects only to the addresses it
 * checked (no second lookup), so a DNS answer that changes between check and connect (rebinding) can't slip through.
 */
public final class EgressPolicy {

    private final boolean allowLocal;

    public EgressPolicy(boolean allowLocal) {
        this.allowLocal = allowLocal;
    }

    public boolean allowLocal() {
        return allowLocal;
    }

    /** Why the URL may not be called before any lookup (scheme, credentials, host), or empty. */
    public Optional<String> refuseUrl(URI url) {
        var scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        var host = url.getHost();
        if (host == null || host.isBlank()) {
            return Optional.of("the URL has no host");
        }
        if (url.getRawUserInfo() != null) {
            return Optional.of("credentials in the URL are not allowed");
        }
        if (!scheme.equals("https") && !(allowLocal && scheme.equals("http"))) {
            return Optional.of("only https:// URLs are allowed");
        }
        return Optional.empty();
    }

    /** Why the resolved addresses may not be called (the first refused one decides), or empty. */
    public Optional<String> refuseAddresses(String host, List<InetAddress> addresses) {
        if (addresses.isEmpty()) {
            return Optional.of(host + " has no address");
        }
        for (var address : addresses) {
            var reason = refuse(address);
            if (reason.isPresent()) {
                return Optional.of(host + " resolves to " + address.getHostAddress() + " (" + reason.get() + ")");
            }
        }
        return Optional.empty();
    }

    /** Why this address may not be called, or empty. */
    public Optional<String> refuse(InetAddress address) {
        if (address.isLoopbackAddress()) {
            return allowLocal ? Optional.empty() : Optional.of("loopback");
        }
        return switch (address) {
            case Inet4Address v4 -> refuse4(v4.getAddress());
            case Inet6Address v6 -> refuse6(v6);
            default -> Optional.of("unknown address family");
        };
    }

    private static Optional<String> refuse4(byte[] a) {
        int b0 = a[0] & 0xff;
        int b1 = a[1] & 0xff;
        int b2 = a[2] & 0xff;
        String reason = null;
        if (b0 == 0) {
            reason = "\"this network\"";
        } else if (b0 == 127) {
            reason = "loopback";
        } else if (b0 == 10 || (b0 == 172 && (b1 & 0xf0) == 16) || (b0 == 192 && b1 == 168)) {
            reason = "private network";
        } else if (b0 == 100 && (b1 & 0xc0) == 64) {
            reason = "carrier-grade NAT"; // also Alibaba's metadata service 100.100.100.200
        } else if (b0 == 169 && b1 == 254) {
            reason = "link-local / cloud metadata";
        } else if (b0 == 192 && b1 == 0 && (b2 == 0 || b2 == 2)) {
            reason = "IETF / documentation range";
        } else if (b0 == 198 && (b1 == 18 || b1 == 19)) {
            reason = "benchmarking range";
        } else if ((b0 == 198 && b1 == 51 && b2 == 100) || (b0 == 203 && b1 == 0 && b2 == 113)) {
            reason = "documentation range";
        } else if (b0 >= 224) {
            reason = "multicast, reserved or broadcast";
        }
        return Optional.ofNullable(reason);
    }

    private Optional<String> refuse6(Inet6Address address) {
        var a = address.getAddress();
        if (address.isAnyLocalAddress()) {
            return Optional.of("unspecified address");
        }
        if (address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
            return Optional.of("link-local / site-local");
        }
        if (address.isMulticastAddress()) {
            return Optional.of("multicast");
        }
        if ((a[0] & 0xfe) == 0xfc) {
            return Optional.of("private network (unique local)"); // fc00::/7, incl. AWS metadata fd00:ec2::254
        }
        if ((a[0] & 0xff) == 0x20 && (a[1] & 0xff) == 0x01 && (a[2] & 0xff) == 0x0d && (a[3] & 0xff) == 0xb8) {
            return Optional.of("documentation range");
        }
        var embedded = embeddedIpv4(a);
        if (embedded.isPresent()) {
            try {
                return refuse(InetAddress.getByAddress(embedded.get())).map(r -> "embeds " + r);
            } catch (UnknownHostException e) {
                return Optional.of("unreadable embedded IPv4 address");
            }
        }
        return Optional.empty();
    }

    /** The IPv4 inside an IPv4-mapped (::ffff:0:0/96), IPv4-compatible (::/96), NAT64 (64:ff9b::/96) or 6to4 (2002::/16) address. */
    private static Optional<byte[]> embeddedIpv4(byte[] a) {
        var zeros10 = true;
        for (var i = 0; i < 10; i++) {
            zeros10 &= a[i] == 0;
        }
        var mapped = zeros10 && (a[10] & 0xff) == 0xff && (a[11] & 0xff) == 0xff;
        var compatible = zeros10 && a[10] == 0 && a[11] == 0;
        var nat64 = (a[0] & 0xff) == 0x00 && (a[1] & 0xff) == 0x64 && (a[2] & 0xff) == 0xff && (a[3] & 0xff) == 0x9b;
        for (var i = 4; nat64 && i < 12; i++) {
            nat64 = a[i] == 0;
        }
        if (mapped || compatible || nat64) {
            return Optional.of(new byte[] {a[12], a[13], a[14], a[15]});
        }
        if ((a[0] & 0xff) == 0x20 && (a[1] & 0xff) == 0x02) {
            return Optional.of(new byte[] {a[2], a[3], a[4], a[5]});
        }
        return Optional.empty();
    }
}
