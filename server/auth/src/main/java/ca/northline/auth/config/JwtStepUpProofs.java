package ca.northline.auth.config;

import ca.northline.auth.application.StepUpProofs;
import ca.northline.auth.domain.Factor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Step-up proofs signed with the same ES256 key as access tokens through the shared {@link JwtEncoder} (so they survive
 * restarts and rotations like access tokens, and carry the key's {@code kid}; the api already trusts the JWK set):
 * {@code iss}, {@code sub}, {@code aud=northline-api/step-up}, {@code token_use=step_up}, {@code acr=mfa},
 * {@code amr}, {@code auth_time}, {@code jti} (one use), valid 5 minutes.
 */
@Component
class JwtStepUpProofs implements StepUpProofs {

    static final String AUDIENCE = "northline-api/step-up";
    static final Duration TTL = Duration.ofMinutes(5);

    private final JwtEncoder encoder;
    private final String issuer;
    private final Clock clock;

    JwtStepUpProofs(
            JwtEncoder encoder,
            @Value("${spring.security.oauth2.authorizationserver.issuer}") String issuer,
            Clock clock) {
        this.encoder = encoder;
        this.issuer = issuer;
        this.clock = clock;
    }

    @Override
    public Proof issue(String userId, Factor factor, Instant authTime) {
        var now = clock.instant();
        var expires = now.plus(TTL);
        var claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(userId)
                .audience(List.of(AUDIENCE))
                .issuedAt(now)
                .expiresAt(expires)
                .id(UUID.randomUUID().toString())
                .claim("token_use", "step_up")
                .claim("acr", "mfa")
                .claim("amr", List.of(factor.amr()))
                .claim("auth_time", authTime.getEpochSecond())
                .build();
        var header = JwsHeader.with(SignatureAlgorithm.ES256).type("JWT").build();
        var token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new Proof(token, expires);
    }
}
