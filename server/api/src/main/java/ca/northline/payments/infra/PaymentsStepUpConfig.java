package ca.northline.payments.infra;

import ca.northline.payments.application.IdempotencyStore;
import ca.northline.payments.application.StepUpVerifier;
import java.time.Clock;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.function.SingletonSupplier;

/**
 * Step-up proof verification. Production: {@link JwtStepUpVerifier} against the auth server's JWK set. Under
 * {@code local} and {@code test} the literal proof {@code dev} is also accepted, because the Studio's dev mode runs
 * without northline-auth (DECISIONS.md, Finance workstream).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
class PaymentsStepUpConfig {

    static Supplier<JwtDecoder> decoder(String issuer) {
        return SingletonSupplier.of(() -> {
            var decoder = NimbusJwtDecoder.withIssuerLocation(issuer)
                    .jwsAlgorithm(SignatureAlgorithm.ES256)
                    .build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
            return decoder;
        });
    }

    @Bean
    @Profile("!local & !test")
    StepUpVerifier stepUpVerifier(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            IdempotencyStore store,
            Clock clock) {
        return new JwtStepUpVerifier(decoder(issuer), store, clock);
    }

    @Bean
    @Profile({"local", "test"})
    StepUpVerifier devStepUpVerifier(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            IdempotencyStore store,
            Clock clock) {
        log.warn("Payments: step-up proof 'dev' is accepted (profile local/test only).");
        var jwt = new JwtStepUpVerifier(decoder(issuer), store, clock);
        return (userId, proof) -> {
            if (!"dev".equals(proof)) {
                jwt.verify(userId, proof);
            }
        };
    }
}
