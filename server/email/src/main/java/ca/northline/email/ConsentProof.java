package ca.northline.email;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * How the circumstances of a CASL consent are minimised before they are stored (S-108; shared since S-157 by the api
 * and northline-auth, which records the sign-up checkboxes): the IP address truncated to its network ({@code
 * 203.0.113.0/24}, {@code 2001:db8:1::/48}), the user agent and the address the consent covered as SHA-256 hashes.
 */
public final class ConsentProof {

    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    private ConsentProof() {}

    /** {@code 203.0.113.42} → {@code 203.0.113.0/24}; IPv6 → its /48; anything else → null. */
    public static @Nullable String ipPrefix(@Nullable String ip) {
        if (ip == null) {
            return null;
        }
        var value = ip.strip();
        var v4 = IPV4.matcher(value);
        if (v4.matches()) {
            return v4.group(1) + "." + v4.group(2) + "." + v4.group(3) + ".0/24";
        }
        if (value.contains(":")) {
            var scope = value.indexOf('%');
            var bare = (scope < 0 ? value : value.substring(0, scope)).toLowerCase(Locale.ROOT);
            if (!bare.matches("[0-9a-f:.]+")) {
                return null;
            }
            var groups = bare.split(":", -1);
            var kept = new StringBuilder();
            for (int i = 0, n = 0; i < groups.length && n < 3; i++) {
                if (groups[i].isEmpty()) {
                    break; // "::" before the third group: the rest is zeros
                }
                kept.append(n == 0 ? "" : ":").append(groups[i]);
                n++;
            }
            return kept.isEmpty() ? "::/48" : kept + "::/48";
        }
        return null;
    }

    /** The user agent's hash; null when there is none. */
    public static @Nullable String userAgentHash(@Nullable String userAgent) {
        return userAgent == null || userAgent.isBlank() ? null : sha256(userAgent.strip());
    }

    /**
     * The hash of the address a consent covered: an email lower-cased, a phone as stored (E.164). Lets staff answer
     * "did this address consent?" after the account is erased, without keeping the address.
     */
    public static String addressHash(String address) {
        return sha256(address.strip().toLowerCase(Locale.ROOT));
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
