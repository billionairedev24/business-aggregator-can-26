package ca.northline.auth.application;

import ca.northline.auth.application.FlowRejected.Reason;
import ca.northline.auth.domain.AuthMessages;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The gate in front of every Settings › Security change (S-19): the person used a second factor in this session within
 * {@code northline.auth.sessions.step-up-max-age} (else {@code 403 step_up_required}: the Studio confirms with
 * {@code /api/auth/step-up/*}, which refreshes the session's factor time, and retries), and the S-9 limits for
 * {@link LimitedAction#SECURITY_CHANGE} per account, IP and session.
 */
@Service
@RequiredArgsConstructor
public class SecurityChanges {

    private final AttemptLimits limits;
    private final SessionProperties props;
    private final Clock clock;

    public void authorize(Caller caller) {
        var last = caller.lastSecondFactorAt();
        if (last == null || last.plus(props.stepUpMaxAge()).isBefore(clock.instant())) {
            throw new FlowRejected(Reason.STEP_UP_REQUIRED, AuthMessages.STEP_UP_REQUIRED);
        }
        limits.consume(LimitedAction.SECURITY_CHANGE, AttemptLimits.Subject.user(caller.userId()));
    }
}
