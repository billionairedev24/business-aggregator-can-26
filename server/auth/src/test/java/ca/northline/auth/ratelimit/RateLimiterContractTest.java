package ca.northline.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.auth.application.LimitScope;
import ca.northline.auth.application.LimitedAction;
import ca.northline.auth.application.RateLimitProperties.Rule;
import ca.northline.auth.application.RateLimiter;
import ca.northline.auth.application.RateLimiter.Limit;
import ca.northline.auth.support.AuthIntegrationTest.MutableClock;
import ca.northline.auth.support.SharedValkey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The limiter algorithm — sliding window, lockout, exponential backoff, reset, several subjects at once — against
 * Valkey (Testcontainers {@code valkey/valkey:8}, the Lua script) and the in-memory fallback, with the same cases.
 */
class RateLimiterContractTest {

    static final Duration LOCKOUT = Duration.ofSeconds(1);

    abstract static class Contract {

        abstract RateLimiter limiter();

        /** Lets time pass (the memory limiter moves its clock, Valkey really waits). */
        abstract void pass(Duration duration) throws InterruptedException;

        Limit limit(LimitedAction action, LimitScope scope, int max, Duration window) {
            var rule = new Rule(max, window, LOCKOUT, LOCKOUT.multipliedBy(3));
            var threshold = action.kind() == LimitedAction.Kind.FAILURES ? max : max + 1;
            return new Limit(action, scope, UUID.randomUUID().toString(), rule, threshold, Duration.ofHours(1));
        }

        @Test
        void requests_allowMax_thenLock() {
            var ip = limit(LimitedAction.SIGN_IN_LOOKUP, LimitScope.IP, 3, Duration.ofMinutes(10));
            for (int i = 0; i < 3; i++) {
                assertThat(limiter().record(List.of(ip)).allowed()).isTrue();
            }
            var denied = limiter().record(List.of(ip));
            assertThat(denied.allowed()).isFalse();
            assertThat(denied.newlyLocked()).containsExactly(LimitScope.IP);
            assertThat(denied.retryAfter()).isBetween(Duration.ofMillis(500), LOCKOUT);
            var again = limiter().record(List.of(ip));
            assertThat(again.allowed()).isFalse();
            assertThat(again.newlyLocked()).isEmpty(); // already locked: not a new lockout
        }

        @Test
        void failures_lockAtMax_andCheckDoesNotCount() {
            var account = limit(LimitedAction.TOTP_VERIFY, LimitScope.ACCOUNT, 3, Duration.ofMinutes(15));
            for (int i = 0; i < 5; i++) {
                assertThat(limiter().check(List.of(account)).allowed()).isTrue();
            }
            assertThat(limiter().record(List.of(account)).allowed()).isTrue();
            assertThat(limiter().record(List.of(account)).allowed()).isTrue();
            assertThat(limiter().record(List.of(account)).allowed()).isFalse();
            assertThat(limiter().check(List.of(account)).allowed()).isFalse();
        }

        @Test
        void lockouts_doubleEachTime_upToTheMaximum() throws InterruptedException {
            var session = limit(LimitedAction.STEP_UP, LimitScope.SESSION, 1, Duration.ofMinutes(15));
            assertThat(limiter().record(List.of(session)).retryAfter()).isBetween(Duration.ofMillis(500), LOCKOUT);
            pass(LOCKOUT.plusMillis(150));
            assertThat(limiter().record(List.of(session)).retryAfter())
                    .isBetween(Duration.ofMillis(1500), LOCKOUT.multipliedBy(2));
            pass(LOCKOUT.multipliedBy(2).plusMillis(150));
            assertThat(limiter().record(List.of(session)).retryAfter()) // 4 s capped at 3 s
                    .isBetween(Duration.ofMillis(2500), LOCKOUT.multipliedBy(3));
        }

        @Test
        void reset_forgetsAttemptsAndEarlierLockouts() throws InterruptedException {
            var account = limit(LimitedAction.BACKUP_CODE_VERIFY, LimitScope.ACCOUNT, 2, Duration.ofMinutes(15));
            limiter().record(List.of(account));
            limiter().record(List.of(account)); // locked (strike 1)
            pass(LOCKOUT.plusMillis(150));
            limiter().record(List.of(account));
            limiter().reset(List.of(account));
            assertThat(limiter().record(List.of(account)).allowed()).isTrue(); // count restarted
            assertThat(limiter().record(List.of(account)).retryAfter()) // and the backoff too: 1 s, not 2 s
                    .isLessThanOrEqualTo(LOCKOUT);
        }

        @Test
        void oneLockedSubject_blocksTheCall_andNothingIsCounted() {
            var ip = limit(LimitedAction.OTP_SEND, LimitScope.IP, 1, Duration.ofHours(1));
            var session = limit(LimitedAction.OTP_SEND, LimitScope.SESSION, 5, Duration.ofHours(1));
            assertThat(limiter().record(List.of(ip, session)).allowed()).isTrue();
            var denied = limiter().record(List.of(ip, session));
            assertThat(denied.allowed()).isFalse();
            assertThat(denied.newlyLocked()).containsExactly(LimitScope.IP);
            for (int i = 0; i < 5; i++) {
                limiter().record(List.of(ip, session)); // refused: the session isn't charged
            }
            // From another IP the same session still has its whole budget left.
            var otherIp = limit(LimitedAction.OTP_SEND, LimitScope.IP, 1, Duration.ofHours(1));
            assertThat(limiter().record(List.of(otherIp, session)).allowed()).isTrue();
        }

        @Test
        void attemptsOutsideTheWindow_areForgotten() throws InterruptedException {
            var ip = limit(LimitedAction.SIGN_IN_LOOKUP, LimitScope.IP, 2, Duration.ofMillis(700));
            limiter().record(List.of(ip));
            limiter().record(List.of(ip));
            pass(Duration.ofMillis(800));
            assertThat(limiter().record(List.of(ip)).allowed()).isTrue();
            assertThat(limiter().record(List.of(ip)).allowed()).isTrue();
            assertThat(limiter().record(List.of(ip)).allowed()).isFalse();
        }
    }

    @Nested
    class InMemory extends Contract {
        final MutableClock clock = new MutableClock();
        final InMemoryRateLimiter limiter = new InMemoryRateLimiter(clock);

        InMemory() {
            clock.set(Instant.parse("2026-10-01T12:00:00Z"));
        }

        @Override
        RateLimiter limiter() {
            return limiter;
        }

        @Override
        void pass(Duration duration) {
            clock.set(clock.instant().plus(duration));
        }
    }

    static LettuceConnectionFactory connections;

    @BeforeAll
    static void connect() {
        connections = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(SharedValkey.host(), SharedValkey.port()));
        connections.afterPropertiesSet();
        connections.start();
    }

    @AfterAll
    static void disconnect() {
        connections.destroy();
    }

    @Nested
    class Valkey extends Contract {
        final RedisRateLimiter limiter = new RedisRateLimiter(new StringRedisTemplate(connections));

        @Override
        RateLimiter limiter() {
            return limiter;
        }

        @Override
        void pass(Duration duration) throws InterruptedException {
            Thread.sleep(duration);
        }

        @Test
        void keysAreHashTaggedAndExpire() {
            var redis = new StringRedisTemplate(connections);
            var ip = limit(LimitedAction.SIGN_IN_LOOKUP, LimitScope.IP, 3, Duration.ofMinutes(10));
            limiter.record(List.of(ip));
            var key = RedisRateLimiter.key(ip, "events");
            assertThat(key).startsWith("nl:auth-rl:{sign_in_lookup}:ip:");
            assertThat(redis.getExpire(key)).isBetween(1L, 600L);
        }
    }
}
