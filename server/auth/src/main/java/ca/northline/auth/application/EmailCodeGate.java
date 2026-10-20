package ca.northline.auth.application;

import ca.northline.auth.application.EmailCodes.StoredCode;
import ca.northline.auth.domain.AuthMessages;
import ca.northline.auth.domain.EmailCode;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * S-154: checks a 6-digit email code against the newest one sent (constant-time, the same work when there is none),
 * counts wrong guesses on the code and in the rate limits ({@link LimitedAction#EMAIL_CODE_VERIFY}), and answers 422
 * {@code code} {@code mismatch | expired | locked} like the phone codes do.
 */
@Component
@RequiredArgsConstructor
class EmailCodeGate {

    /** Compared when there is no code, so an unknown address costs the same. */
    private static final String DECOY = EmailCode.hashOfCode("decoy");

    private final EmailCodes codes;
    private final AttemptLimits limits;
    private final AccountProperties props;
    private final Clock clock;

    /** The valid (still unused) code; otherwise throws. */
    StoredCode check(Optional<StoredCode> stored, String code, AttemptLimits.Subject who) {
        limits.guard(LimitedAction.EMAIL_CODE_VERIFY, who);
        var presented = EmailCode.hashOfCode(code);
        var matches =
                EmailCode.sameHash(presented, stored.map(StoredCode::codeHash).orElse(DECOY));
        if (stored.isEmpty()) {
            throw limits.failed(LimitedAction.EMAIL_CODE_VERIFY, who, wrong());
        }
        var c = stored.get();
        if (c.failedAttempts() >= props.codeMaxAttempts()) {
            throw InvalidInput.of("code", "locked", AuthMessages.CODE_LOCKED);
        }
        if (c.usedAt() != null || !c.expiresAt().isAfter(clock.instant())) {
            throw InvalidInput.of("code", "expired", AuthMessages.CODE_EXPIRED);
        }
        if (!matches) {
            var failures = codes.failed(c.id());
            var answer = failures >= props.codeMaxAttempts()
                    ? InvalidInput.of("code", "locked", AuthMessages.CODE_LOCKED)
                    : wrong();
            throw limits.failed(LimitedAction.EMAIL_CODE_VERIFY, who, answer);
        }
        return c;
    }

    /** Uses the code up (single use); a concurrent second use is answered as expired. */
    void use(StoredCode code, AttemptLimits.Subject who) {
        if (!codes.use(code.id(), clock.instant())) {
            throw InvalidInput.of("code", "expired", AuthMessages.CODE_EXPIRED);
        }
        limits.succeeded(LimitedAction.EMAIL_CODE_VERIFY, who);
    }

    private static InvalidInput wrong() {
        return InvalidInput.of("code", "mismatch", AuthMessages.CODE_WRONG);
    }
}
