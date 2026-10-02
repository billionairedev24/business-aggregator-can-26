package ca.northline.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ca.northline.auth.application.AttemptLimits;
import ca.northline.auth.application.FlowRejected;
import ca.northline.auth.application.LimitScope;
import ca.northline.auth.application.LimitedAction;
import ca.northline.auth.application.RateLimitProperties;
import ca.northline.auth.application.RateLimitProperties.Rule;
import ca.northline.auth.application.RequestOrigin;
import ca.northline.auth.application.SignInLog;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * S-104 (SMS pumping): codes texted to many different numbers from many addresses stay under every per-account, per-IP
 * and per-session limit. The platform budget counts them all together.
 */
class PlatformSmsBudgetTest {

    private final RequestOrigin origin = mock(RequestOrigin.class);

    @Test
    void textsToManyNumbersFromManyAddresses_stopAtThePlatformBudget() {
        var perSubject = new Rule(5, Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1));
        var platform = new Rule(10, Duration.ofHours(1), Duration.ofMinutes(15), Duration.ofHours(1));
        var limits = new AttemptLimits(
                new InMemoryRateLimiter(Clock.systemUTC()),
                new RateLimitProperties(
                        RateLimitProperties.Store.MEMORY,
                        RateLimitProperties.WhenUnavailable.CLOSED,
                        Duration.ofHours(24),
                        Map.of(
                                LimitedAction.OTP_SEND,
                                Map.of(
                                        LimitScope.ACCOUNT, perSubject,
                                        LimitScope.IP, perSubject,
                                        LimitScope.SESSION, perSubject,
                                        LimitScope.PLATFORM, platform))),
                origin,
                mock(SignInLog.class));

        for (var i = 0; i < 10; i++) {
            from("198.51.100." + i, "session-" + i);
            var mobile = AttemptLimits.Subject.identifier("+1587555%04d".formatted(i), null);
            assertThatCode(() -> limits.consume(LimitedAction.OTP_SEND, mobile))
                    .as("text %d", i + 1)
                    .doesNotThrowAnyException();
        }

        from("203.0.113.99", "session-new");
        var eleventh = AttemptLimits.Subject.identifier("+15875559999", null);
        assertThatThrownBy(() -> limits.consume(LimitedAction.OTP_SEND, eleventh))
                .isInstanceOfSatisfying(
                        FlowRejected.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.getReason())
                                .isEqualTo(FlowRejected.Reason.RATE_LIMITED));
    }

    private void from(String ip, String session) {
        when(origin.ip()).thenReturn(ip);
        when(origin.sessionId()).thenReturn(session);
    }
}
