package ca.northline.auth.dpop;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.DPoPProofContext;
import org.springframework.security.oauth2.jwt.DPoPProofJwtDecoderFactory;
import org.springframework.security.oauth2.jwt.DPoPProofReplayValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuedAtValidator;
import org.springframework.security.oauth2.jwt.JwtValidationException;

/**
 * Verifies a DPoP proof at the token endpoint with Spring Security's {@link DPoPProofJwtDecoderFactory} ({@code typ
 * dpop+jwt}, an EC or RSA {@code jwk} header without private parts, signature, {@code htm}, {@code htu}, {@code iat}
 * within 30 s) plus what it leaves to the application: the {@code jti} is single-use across every instance (Valkey,
 * {@link ReplayCache}) and the proof carries a current server {@code nonce}.
 *
 * <p>{@code iat} is checked against the system clock with Spring's 30 s skew — the same check Spring Authorization
 * Server repeats when it processes the grant, so a looser window here would buy nothing.
 */
final class DpopProofs {

    static final Duration SKEW = Duration.ofSeconds(30);
    static final String USE_DPOP_NONCE = "use_dpop_nonce";

    private final DPoPProofJwtDecoderFactory decoders = new DPoPProofJwtDecoderFactory();

    DpopProofs(DpopState state, DpopNonces nonces) {
        var clock = Clock.systemUTC(); // Spring Authorization Server's own DPoP check uses the system clock too
        var issuedAt = new JwtIssuedAtValidator(true);
        issuedAt.setClockSkew(SKEW);
        issuedAt.setClock(clock);
        var replay = new DPoPProofReplayValidator(new ReplayCache(state, clock));
        replay.setClockSkew(SKEW);
        replay.setClock(clock);
        OAuth2TokenValidator<Jwt> nonce = jwt -> nonces.accepts(jwt.getClaimAsString("nonce"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        USE_DPOP_NONCE, "Use the nonce from the DPoP-Nonce header in the DPoP proof.", null));
        // Custom validators run first: a stale nonce is refused before the proof id is spent.
        decoders.setJwtValidatorFactory(
                DPoPProofJwtDecoderFactory.createDefaultJwtValidatorFactory(List.of(nonce, issuedAt, replay)));
    }

    /** The verified proof, or {@link Rejected} with the RFC 9449 error code. */
    Jwt verify(String proof, String method, String targetUri) {
        var context = DPoPProofContext.withDPoPProof(proof)
                .method(method)
                .targetUri(targetUri)
                .build();
        try {
            return decoders.createDecoder(context).decode(proof);
        } catch (JwtValidationException e) {
            var nonceMissing = e.getErrors().stream().anyMatch(err -> USE_DPOP_NONCE.equals(err.getErrorCode()));
            throw new Rejected(
                    nonceMissing ? USE_DPOP_NONCE : OAuth2ErrorCodes.INVALID_DPOP_PROOF,
                    nonceMissing ? "Use the nonce from the DPoP-Nonce header." : "The DPoP proof is not valid.");
        } catch (DpopState.Unavailable e) {
            throw e;
        } catch (RuntimeException e) {
            if (e.getCause() instanceof DpopState.Unavailable unavailable) {
                throw unavailable;
            }
            throw new Rejected(OAuth2ErrorCodes.INVALID_DPOP_PROOF, "The DPoP proof is not valid.");
        }
    }

    /** A refused proof: {@code invalid_dpop_proof} or {@code use_dpop_nonce}. */
    static final class Rejected extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String error;

        Rejected(String error, String description) {
            super(description);
            this.error = error;
        }

        String error() {
            return error;
        }
    }
}
