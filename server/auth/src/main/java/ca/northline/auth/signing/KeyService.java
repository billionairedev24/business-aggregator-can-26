package ca.northline.auth.signing;

import java.security.interfaces.ECPublicKey;

/**
 * What {@link RemoteSigningKeys} needs from a cloud key service: an EC P-256 key's public half, and an ES256 signature
 * over a SHA-256 digest computed inside the service (the private key never leaves it). One adapter per cloud.
 */
interface KeyService {

    /** Short name for logs ({@code aws}, {@code gcp}, {@code azure}). */
    String name();

    ECPublicKey publicKey(String keyId);

    /** Signs a SHA-256 digest; returns the JWS form {@code R || S} (64 bytes). */
    byte[] signDigest(String keyId, byte[] sha256);
}
