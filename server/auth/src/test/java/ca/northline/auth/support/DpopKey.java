package ca.northline.auth.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A mobile app's DPoP key (a freshly generated P-256 key, as the secure enclave would hold) and its proofs. */
public final class DpopKey {

    private final ECKey key;

    private DpopKey(ECKey key) {
        this.key = key;
    }

    public static DpopKey generate() {
        try {
            return new DpopKey(new ECKeyGenerator(Curve.P_256).generate());
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** RFC 7638 thumbprint of the public key: the {@code cnf.jkt} of tokens bound to it. */
    public String thumbprint() {
        try {
            return key.toPublicJWK().computeThumbprint().toString();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A proof for a token-endpoint request. */
    public String proof(String method, String uri, @Nullable String nonce) {
        return proof(method, uri, nonce, null, Instant.now());
    }

    /** A proof for a resource request: {@code ath} = hash of the access token. */
    public String proof(String method, String uri, @Nullable String nonce, @Nullable String accessToken, Instant iat) {
        var claims = new JWTClaimsSet.Builder()
                .jwtID(UUID.randomUUID().toString())
                .claim("htm", method)
                .claim("htu", uri)
                .issueTime(Date.from(iat));
        if (nonce != null) {
            claims.claim("nonce", nonce);
        }
        if (accessToken != null) {
            claims.claim("ath", sha256(accessToken));
        }
        var header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(new JOSEObjectType("dpop+jwt"))
                .jwk(key.toPublicJWK())
                .build();
        try {
            var jwt = new SignedJWT(header, claims.build());
            jwt.sign(new ECDSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String value) {
        try {
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
