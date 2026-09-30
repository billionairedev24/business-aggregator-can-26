package ca.northline.payments.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WebhookRateLimiterTest {

    private static final class MovableClock extends Clock {
        final AtomicReference<Instant> now;

        MovableClock(Instant start) {
            now = new AtomicReference<>(start);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    @Test
    void limitsEachAddressPerMinute_andResetsTheNextMinute() {
        var clock = new MovableClock(Instant.parse("2026-10-01T15:00:05Z"));
        var limiter = new WebhookRateLimiter(3, clock);
        assertThat(limiter.allow("54.187.174.169")).isTrue();
        assertThat(limiter.allow("54.187.174.169")).isTrue();
        assertThat(limiter.allow("54.187.174.169")).isTrue();
        assertThat(limiter.allow("54.187.174.169")).isFalse();
        assertThat(limiter.allow("10.0.0.9")).isTrue();

        clock.now.set(Instant.parse("2026-10-01T15:01:00Z"));
        assertThat(limiter.allow("54.187.174.169")).isTrue();
    }
}
