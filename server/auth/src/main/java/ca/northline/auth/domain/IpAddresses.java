package ca.northline.auth.domain;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Client addresses as the session list shows them (S-19): approximate, never the whole address — IPv4 without its last
 * octet ({@code 203.0.113.x}), IPv6 as its /48 ({@code 2001:db8:85a3::/48}).
 */
public final class IpAddresses {

    private static final Pattern LITERAL = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    private IpAddresses() {}

    public static @Nullable String approximate(@Nullable String ip) {
        if (ip == null || !LITERAL.matcher(ip).matches()) {
            return null;
        }
        try {
            return switch (InetAddress.getByName(ip)) { // a literal: no DNS lookup
                case Inet4Address v4 -> {
                    var b = v4.getAddress();
                    yield "%d.%d.%d.x".formatted(b[0] & 0xff, b[1] & 0xff, b[2] & 0xff);
                }
                case Inet6Address v6 -> {
                    var b = v6.getAddress();
                    yield "%x:%x:%x::/48"
                            .formatted(
                                    ((b[0] & 0xff) << 8) | (b[1] & 0xff),
                                    ((b[2] & 0xff) << 8) | (b[3] & 0xff),
                                    ((b[4] & 0xff) << 8) | (b[5] & 0xff));
                }
                default -> null;
            };
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
