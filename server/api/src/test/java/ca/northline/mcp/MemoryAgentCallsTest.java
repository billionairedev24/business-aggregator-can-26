package ca.northline.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** S-127: the per-minute window and the single-use pending marks behind confirmations. */
class MemoryAgentCallsTest {

    MutableClock clock = new MutableClock(Instant.parse("2026-10-01T18:00:00Z"));
    MemoryAgentCalls calls = new MemoryAgentCalls(clock);

    @Test
    void countsPerMinute() {
        for (var i = 0; i < 3; i++) {
            assertThat(calls.allow("a", 3)).isTrue();
        }
        assertThat(calls.allow("a", 3)).isFalse();
        assertThat(calls.allow("b", 3)).isTrue();
        clock.advance(Duration.ofMinutes(1));
        assertThat(calls.allow("a", 3)).isTrue();
    }

    @Test
    void aPendingMarkIsConsumedOnceAndExpires() {
        assertThat(calls.putIfAbsent("pending:k", "t", Duration.ofMinutes(5))).isTrue();
        assertThat(calls.putIfAbsent("pending:k", "t", Duration.ofMinutes(5))).isFalse();
        assertThat(calls.remove("pending:k")).isTrue();
        assertThat(calls.remove("pending:k")).isFalse();

        calls.putIfAbsent("pending:j", "t", Duration.ofMinutes(5));
        clock.advance(Duration.ofMinutes(6));
        assertThat(calls.get("pending:j")).isEmpty();
        assertThat(calls.remove("pending:j")).isFalse();
        assertThat(calls.putIfAbsent("pending:j", "u", Duration.ofMinutes(5))).isTrue();
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
