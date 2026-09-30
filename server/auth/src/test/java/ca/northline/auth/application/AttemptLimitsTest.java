package ca.northline.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ca.northline.auth.application.RateLimitProperties.Rule;
import ca.northline.auth.application.RateLimitProperties.Store;
import ca.northline.auth.application.RateLimitProperties.WhenUnavailable;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** S-20: what the limits do while their store (Valkey) can't be reached — fail open or closed, per action. */
class AttemptLimitsTest {

    private final RateLimiter limiter = mock(RateLimiter.class);
    private final RequestOrigin origin = mock(RequestOrigin.class);
    private final SignInLog audit = mock(SignInLog.class);
    private final AttemptLimits.Subject who = AttemptLimits.Subject.identifier("amara@example.ca", null);

    @BeforeEach
    void storeIsDown() {
        var down = new RateLimiter.Unavailable("Valkey: connection refused", new IllegalStateException());
        when(limiter.check(anyList())).thenThrow(down);
        when(limiter.record(anyList())).thenThrow(down);
        when(origin.ip()).thenReturn("203.0.113.7");
        when(origin.sessionId()).thenReturn("s1");
    }

    private AttemptLimits limits(WhenUnavailable policy) {
        var rule = new Rule(5, Duration.ofMinutes(15), Duration.ofMinutes(15), Duration.ofHours(24));
        var rules = new EnumMap<LimitedAction, Map<LimitScope, Rule>>(LimitedAction.class);
        for (var action : LimitedAction.values()) {
            rules.put(action, Map.of(LimitScope.ACCOUNT, rule, LimitScope.IP, rule, LimitScope.SESSION, rule));
        }
        return new AttemptLimits(
                limiter, new RateLimitProperties(Store.REDIS, policy, Duration.ofHours(24), rules), origin, audit);
    }

    @Test
    void closed_refusesEveryCodeAndFactorPath() {
        var limits = limits(WhenUnavailable.CLOSED);
        for (var action : LimitedAction.values()) {
            if (!action.guardsSecret()) {
                continue;
            }
            var call = action.kind() == LimitedAction.Kind.REQUESTS
                    ? (Runnable) () -> limits.consume(action, who)
                    : (Runnable) () -> limits.guard(action, who);
            assertThatThrownBy(call::run).as(action.code()).isInstanceOfSatisfying(FlowRejected.class, e -> {
                assertThat(e.getReason()).isEqualTo(FlowRejected.Reason.UNAVAILABLE);
                assertThat(e.getRetryAfterSeconds()).isEqualTo(30L);
            });
        }
    }

    @Test
    void closed_aWrongAnswerIsAnsweredUnavailable_notWrong() {
        var wrong = new IllegalArgumentException("wrong code");
        var outcome = limits(WhenUnavailable.CLOSED).failed(LimitedAction.TOTP_VERIFY, who, wrong);
        assertThat(outcome)
                .isInstanceOfSatisfying(
                        FlowRejected.class, e -> assertThat(e.getReason()).isEqualTo(FlowRejected.Reason.UNAVAILABLE));
    }

    @Test
    void closed_letsTheLookupAndSecurityChangesThrough() {
        var limits = limits(WhenUnavailable.CLOSED);
        assertThatCode(() -> limits.consume(LimitedAction.SIGN_IN_LOOKUP, who)).doesNotThrowAnyException();
        assertThatCode(() -> limits.consume(LimitedAction.SECURITY_CHANGE, AttemptLimits.Subject.user("u1")))
                .doesNotThrowAnyException();
    }

    @Test
    void open_letsEverythingThrough() {
        var limits = limits(WhenUnavailable.OPEN);
        for (var action : LimitedAction.values()) {
            assertThatCode(() -> limits.consume(action, who)).doesNotThrowAnyException();
            assertThatCode(() -> limits.guard(action, who)).doesNotThrowAnyException();
        }
        var wrong = new IllegalArgumentException("wrong code");
        assertThat(limits.failed(LimitedAction.OTP_VERIFY, who, wrong)).isSameAs(wrong);
    }

    @Test
    void theCodeAndFactorPaths_areExactlyTheGuessableOnes() {
        assertThat(LimitedAction.values())
                .filteredOn(LimitedAction::guardsSecret)
                .containsExactlyInAnyOrder(
                        LimitedAction.OTP_SEND,
                        LimitedAction.OTP_VERIFY,
                        LimitedAction.TOTP_VERIFY,
                        LimitedAction.BACKUP_CODE_VERIFY,
                        LimitedAction.PASSKEY_ASSERTION,
                        LimitedAction.STEP_UP);
    }
}
