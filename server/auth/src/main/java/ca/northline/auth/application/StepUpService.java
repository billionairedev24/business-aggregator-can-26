package ca.northline.auth.application;

import ca.northline.auth.application.FlowRejected.Reason;
import ca.northline.auth.domain.AuthMessages;
import ca.northline.auth.domain.Factor;
import ca.northline.auth.domain.Totp;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Step-up for a signed-in person (Finance workstream): confirm again with a passkey or the authenticator app and get a
 * {@link StepUpProofs.Proof}. The factor must belong to the session's user. Five failures lock step-up for the session;
 * the S-9 limits ({@link AttemptLimits}) also count failures per user and IP across sessions.
 */
@Service
@RequiredArgsConstructor
public class StepUpService {

    static final int MAX_FAILURES = 5;

    private final PasskeyService passkeys;
    private final SecondFactors factors;
    private final FlowStore flow;
    private final StepUpProofs proofs;
    private final AttemptLimits limits;
    private final Clock clock;

    /** Options for {@code navigator.credentials.get()} limited to the user's own passkeys. */
    public String passkeyOptions(String userId) {
        guard(userId);
        var options = passkeys.requestOptions(userId);
        flow.put(FlowStore.STEP_UP_PASSKEY, options);
        return passkeys.toJson(options);
    }

    public StepUpProofs.Proof withPasskey(String userId, String assertionJson) {
        guard(userId);
        var options = flow.get(FlowStore.STEP_UP_PASSKEY)
                .orElseThrow(() -> new FlowRejected(Reason.NOT_STARTED, "Start the passkey confirmation again."));
        flow.remove(FlowStore.STEP_UP_PASSKEY);
        String owner;
        try {
            owner = passkeys.authenticate(options, assertionJson);
        } catch (InvalidInput e) {
            throw failed(userId, e);
        }
        if (!userId.equals(owner)) {
            throw failed(userId, InvalidInput.of("credential", "passkey", AuthMessages.PASSKEY_FAILED));
        }
        return succeed(userId, Factor.PASSKEY);
    }

    @Transactional
    public StepUpProofs.Proof withTotp(String userId, String code) {
        guard(userId);
        var step = factors.findTotp(userId).flatMap(t -> {
            var s = Totp.verify(t.secret(), code.trim(), clock.instant(), t.lastUsedStep());
            return s.isPresent() ? java.util.Optional.of(s.getAsLong()) : java.util.Optional.<Long>empty();
        });
        if (step.isEmpty() || !factors.markTotpUsed(userId, step.get())) {
            throw failed(userId, InvalidInput.of("code", "mismatch", AuthMessages.SIGN_IN_CODE_WRONG));
        }
        return succeed(userId, Factor.TOTP);
    }

    private StepUpProofs.Proof succeed(String userId, Factor factor) {
        flow.remove(FlowStore.STEP_UP_FAILURES);
        limits.succeeded(LimitedAction.STEP_UP, AttemptLimits.Subject.user(userId));
        return proofs.issue(userId, factor, clock.instant());
    }

    private void guard(String userId) {
        limits.guard(LimitedAction.STEP_UP, AttemptLimits.Subject.user(userId));
        if (flow.get(FlowStore.STEP_UP_FAILURES).orElse(0) >= MAX_FAILURES) {
            throw new FlowRejected(Reason.LOCKED, AuthMessages.TOO_MANY_ATTEMPTS);
        }
    }

    private RuntimeException failed(String userId, InvalidInput error) {
        var failures = flow.get(FlowStore.STEP_UP_FAILURES).orElse(0) + 1;
        flow.put(FlowStore.STEP_UP_FAILURES, failures);
        RuntimeException outcome =
                failures >= MAX_FAILURES ? new FlowRejected(Reason.LOCKED, AuthMessages.TOO_MANY_ATTEMPTS) : error;
        return limits.failed(LimitedAction.STEP_UP, AttemptLimits.Subject.user(userId), outcome);
    }
}
