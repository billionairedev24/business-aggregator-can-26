package ca.northline.auth.signing;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.jca.JCAContext;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.util.Date;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtEncodingException;

/**
 * {@link JwtEncoder} that asks {@link SigningKeys} to sign, so the private key can stay in a KMS. Spring Authorization
 * Server picks up this bean for access and ID tokens; {@code JwtStepUpProofs} uses it for step-up proofs. Only ES256;
 * every token gets the active key's {@code kid}. Header and claims are converted the way {@code NimbusJwtEncoder} does.
 */
@RequiredArgsConstructor
public final class KeyStoreJwtEncoder implements JwtEncoder {

    private final SigningKeys keys;

    @Override
    public Jwt encode(JwtEncoderParameters parameters) {
        var headers = parameters.getJwsHeader();
        var algorithm = headers == null ? null : headers.getAlgorithm();
        if (algorithm != null && !SignatureAlgorithm.ES256.equals(algorithm)) {
            throw new JwtEncodingException("Only ES256 is supported, not " + algorithm);
        }
        var key = keys.active();
        var header = header(headers, key.keyId());
        var claims = parameters.getClaims();
        var jwt = new SignedJWT(header, claims(claims));
        try {
            jwt.sign(new PortSigner(key));
        } catch (com.nimbusds.jose.JOSEException | RuntimeException e) {
            throw new JwtEncodingException("Signing with key " + key.keyId() + " failed: " + e.getMessage(), e);
        }
        return new Jwt(
                jwt.serialize(),
                claims.getIssuedAt(),
                claims.getExpiresAt(),
                header.toJSONObject(),
                claims.getClaims());
    }

    private static JWSHeader header(@Nullable JwsHeader headers, String keyId) {
        var builder = new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keyId);
        if (headers != null) {
            var type = headers.getType();
            if (type != null) {
                builder.type(new JOSEObjectType(type));
            }
            var contentType = headers.getContentType();
            if (contentType != null) {
                builder.contentType(contentType);
            }
        }
        return builder.build();
    }

    private static JWTClaimsSet claims(JwtClaimsSet claims) {
        var builder = new JWTClaimsSet.Builder();
        var issuer = claims.getClaims().get("iss");
        if (issuer != null) {
            builder.issuer(issuer.toString());
        }
        var subject = claims.getSubject();
        if (subject != null) {
            builder.subject(subject);
        }
        var audience = claims.getAudience();
        if (audience != null && !audience.isEmpty()) {
            builder.audience(audience);
        }
        var expiresAt = claims.getExpiresAt();
        if (expiresAt != null) {
            builder.expirationTime(Date.from(expiresAt));
        }
        var notBefore = claims.getNotBefore();
        if (notBefore != null) {
            builder.notBeforeTime(Date.from(notBefore));
        }
        var issuedAt = claims.getIssuedAt();
        if (issuedAt != null) {
            builder.issueTime(Date.from(issuedAt));
        }
        var id = claims.getId();
        if (id != null) {
            builder.jwtID(id);
        }
        claims.getClaims().forEach((name, value) -> {
            if (!JWTClaimsSet.getRegisteredNames().contains(name)) {
                builder.claim(name, value);
            }
        });
        return builder.build();
    }

    /** Nimbus signer backed by the port (the signature may be computed by a KMS). */
    private record PortSigner(SigningKeys.SigningKey key) implements JWSSigner {

        @Override
        public Base64URL sign(JWSHeader header, byte[] signingInput) {
            return Base64URL.encode(key.sign(signingInput));
        }

        @Override
        public Set<JWSAlgorithm> supportedJWSAlgorithms() {
            return Set.of(JWSAlgorithm.ES256);
        }

        @Override
        public JCAContext getJCAContext() {
            return new JCAContext();
        }
    }
}
