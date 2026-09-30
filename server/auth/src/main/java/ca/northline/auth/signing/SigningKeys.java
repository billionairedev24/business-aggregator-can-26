package ca.northline.auth.signing;

import com.nimbusds.jose.jwk.ECKey;
import java.util.List;

/**
 * Outbound port: the ES256 keys that sign every token northline-auth issues (access, ID and step-up tokens).
 *
 * <p>{@link #active()} is the key that signs now; {@link #published()} is what {@code /oauth2/jwks} serves — the active
 * key, a key about to become active, and keys retired recently enough that tokens they signed are still valid. Every
 * key id is the key's RFC 7638 thumbprint, so all instances agree on it without coordination.
 */
public interface SigningKeys {

    /** The key new tokens are signed with. */
    SigningKey active();

    /** Public keys only (no private material), in the order the JWK set lists them. */
    List<ECKey> published();

    /** One line per key for logs and the rotation command. */
    List<KeyState> describe();

    /** A key that can sign. */
    interface SigningKey {

        /** The {@code kid} header of tokens this key signs. */
        String keyId();

        /**
         * Signs a JWS signing input ({@code base64url(header) '.' base64url(payload)}) with ES256 and returns the JWS
         * signature: {@code R || S}, 64 bytes (RFC 7518 § 3.4), not DER.
         */
        byte[] sign(byte[] signingInput);
    }

    /** What a key is doing. */
    record KeyState(String keyId, Status status, String detail) {

        /** Lifecycle of a key. */
        public enum Status {
            /** Published, not signing yet (JWK set caches pick it up before it signs). */
            NEXT,
            /** Signs new tokens. */
            ACTIVE,
            /** Published only so tokens it signed stay verifiable. */
            RETIRING
        }
    }
}
