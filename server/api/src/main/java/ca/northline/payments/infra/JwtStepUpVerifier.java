package ca.northline.payments.infra;

import ca.northline.payments.application.IdempotencyStore;
import ca.northline.payments.application.StepUpRequired;
import ca.northline.payments.application.StepUpVerifier;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Verifies a step-up proof issued by northline-auth ({@code POST /api/auth/step-up/*}): an ES256 JWT from the same
 * issuer as access tokens, audience {@value #AUDIENCE} (so it is never accepted as an access token), claim
 * {@code token_use=step_up}, {@code sub} = the caller, {@code auth_time} at most {@link #MAX_AGE} ago, and a
 * {@code jti} that is consumed on first use.
 */
@Slf4j
@RequiredArgsConstructor
class JwtStepUpVerifier implements StepUpVerifier {

    static final String AUDIENCE = "northline-api/step-up";
    static final Duration MAX_AGE = Duration.ofMinutes(5);
    static final String MESSAGE = "Confirm with your passkey. Payouts always require a fresh authentication.";

    /** Built lazily: the auth server's JWK set is fetched on first use, not at start-up. */
    private final Supplier<JwtDecoder> decoder;

    private final IdempotencyStore used;
    private final Clock clock;

    @Override
    public void verify(String userId, @Nullable String proof) {
        if (proof == null || proof.isBlank()) {
            throw new StepUpRequired(MESSAGE);
        }
        Jwt jwt;
        try {
            jwt = decoder.get().decode(proof);
        } catch (RuntimeException e) {
            log.info("Step-up proof rejected: {}", e.getMessage());
            throw new StepUpRequired(MESSAGE);
        }
        var authTime = jwt.getClaimAsInstant("auth_time");
        var now = clock.instant();
        var audience = jwt.getAudience();
        var jti = jwt.getId();
        if (!"step_up".equals(jwt.getClaimAsString("token_use"))
                || audience == null
                || !audience.contains(AUDIENCE)
                || !userId.equals(jwt.getSubject())
                || authTime == null
                || authTime.isBefore(now.minus(MAX_AGE))
                || authTime.isAfter(now.plus(Duration.ofMinutes(1)))
                || jti == null) {
            throw new StepUpRequired(MESSAGE);
        }
        if (used.claim("step-up", jti, userId, MAX_AGE.multipliedBy(2)).isPresent()) {
            throw new StepUpRequired(MESSAGE);
        }
    }
}
