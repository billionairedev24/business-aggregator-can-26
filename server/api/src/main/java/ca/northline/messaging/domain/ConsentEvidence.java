package ca.northline.messaging.domain;

import ca.northline.email.ConsentProof;
import org.jspecify.annotations.Nullable;

/**
 * The circumstances of a consent, minimised before they are stored (S-108; PIPEDA and Law 25 data minimisation): the
 * IP address truncated to its network ({@code 203.0.113.0/24}, {@code 2001:db8:1::/48}) and the user agent as a
 * SHA-256 hash — enough to show a request came from the person's own browser or app, not enough to track them. The
 * rules are the shared library's {@link ConsentProof} (northline-auth records the sign-up consents with them, S-157).
 */
public record ConsentEvidence(
        @Nullable String ipPrefix, @Nullable String userAgentHash) {

    public static final ConsentEvidence NONE = new ConsentEvidence(null, null);

    public static ConsentEvidence of(@Nullable String ip, @Nullable String userAgent) {
        return new ConsentEvidence(ConsentProof.ipPrefix(ip), ConsentProof.userAgentHash(userAgent));
    }

    /**
     * The hash of the address a consent covered: an email lower-cased, a phone as stored (E.164). Lets staff answer
     * "did this address consent?" after the account is erased, without keeping the address.
     */
    public static String addressHash(String address) {
        return ConsentProof.addressHash(address);
    }
}
