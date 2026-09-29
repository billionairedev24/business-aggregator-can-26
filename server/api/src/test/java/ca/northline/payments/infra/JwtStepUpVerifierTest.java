package ca.northline.payments.infra;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.payments.application.IdempotencyStore;
import ca.northline.payments.application.StepUpRequired;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Step-up proofs as northline-auth signs them: accepted once, fresh, for the caller, with the step-up audience. */
class JwtStepUpVerifierTest {

    private static final Instant NOW = Instant.parse("2026-09-29T16:00:00Z");

    private NimbusJwtEncoder encoder;
    private JwtStepUpVerifier verifier;

    /** In-memory stand-in for the idempotency store. */
    static final class MemoryStore implements IdempotencyStore {
        private final Map<String, Stored> keys = new ConcurrentHashMap<>();

        @Override
        public Optional<Stored> claim(String scope, String key, String fingerprint, Duration ttl) {
            return Optional.ofNullable(keys.putIfAbsent(scope + key, new Stored(fingerprint, null, null)));
        }

        @Override
        public void complete(String scope, String key, int status, String body) {}

        @Override
        public void release(String scope, String key) {}
    }

    @BeforeEach
    void keys() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = generator.generateKeyPair();
        var key = new ECKey.Builder(Curve.P_256, (ECPublicKey) pair.getPublic())
                .privateKey(pair.getPrivate())
                .keyID("k1")
                .algorithm(JWSAlgorithm.ES256)
                .build();
        encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        var decoder = NimbusJwtDecoder.withJwkSource(new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK())))
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                .build();
        decoder.setJwtValidator(jwt -> org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success());
        verifier = new JwtStepUpVerifier(() -> decoder, new MemoryStore(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String proof(String sub, String audience, String use, Instant authTime) {
        var claims = JwtClaimsSet.builder()
                .issuer("http://localhost:9000")
                .subject(sub)
                .audience(List.of(audience))
                .issuedAt(authTime)
                .expiresAt(authTime.plus(Duration.ofMinutes(5)))
                .id(UUID.randomUUID().toString())
                .claim("token_use", use)
                .claim("auth_time", authTime.getEpochSecond())
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(SignatureAlgorithm.ES256).build(), claims))
                .getTokenValue();
    }

    @Test
    void freshProofForTheCaller_isAcceptedOnce() {
        var p = proof("u1", "northline-api/step-up", "step_up", NOW.minusSeconds(30));
        assertThatCode(() -> verifier.verify("u1", p)).doesNotThrowAnyException();
        assertThatThrownBy(() -> verifier.verify("u1", p)).isInstanceOf(StepUpRequired.class);
    }

    @Test
    void rejected_whenMissingStaleSomeoneElsesOrAnAccessToken() {
        assertThatThrownBy(() -> verifier.verify("u1", null)).isInstanceOf(StepUpRequired.class);
        assertThatThrownBy(() -> verifier.verify("u1", "garbage")).isInstanceOf(StepUpRequired.class);
        assertThatThrownBy(() -> verifier.verify(
                        "u1", proof("u1", "northline-api/step-up", "step_up", NOW.minus(Duration.ofMinutes(6)))))
                .isInstanceOf(StepUpRequired.class);
        assertThatThrownBy(() ->
                        verifier.verify("u1", proof("u2", "northline-api/step-up", "step_up", NOW.minusSeconds(5))))
                .isInstanceOf(StepUpRequired.class);
        assertThatThrownBy(() -> verifier.verify("u1", proof("u1", "northline-api", "step_up", NOW.minusSeconds(5))))
                .isInstanceOf(StepUpRequired.class);
        assertThatThrownBy(() ->
                        verifier.verify("u1", proof("u1", "northline-api/step-up", "access", NOW.minusSeconds(5))))
                .isInstanceOf(StepUpRequired.class);
    }
}
