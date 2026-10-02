package ca.northline.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** S-113: the outbox backlog gauges read the registry once per 15 s and never fail the export. */
class OutboxBacklogTest {

    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final AtomicInteger queries = new AtomicInteger();
    Instant now = Instant.parse("2026-10-02T12:00:00Z");

    final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
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
    };

    @Test
    void reportsPendingPublicationsAndTheOldestAge() {
        new OutboxBacklog(() -> new OutboxBacklog.Snapshot(3 + queries.getAndIncrement(), 42.5), clock).bindTo(meters);

        assertThat(meters.get(OutboxBacklog.PENDING).gauge().value()).isEqualTo(3);
        assertThat(meters.get(OutboxBacklog.OLDEST_AGE).gauge().value()).isEqualTo(42.5);
        assertThat(meters.get(OutboxBacklog.OLDEST_AGE).gauge().getId().getBaseUnit())
                .isEqualTo("seconds");
        assertThat(queries).hasValue(1); // both gauges from one query

        now = now.plusSeconds(16);
        assertThat(meters.get(OutboxBacklog.PENDING).gauge().value()).isEqualTo(4);
    }

    @Test
    void anUnreadableRegistryLeavesTheGaugesEmpty() {
        new OutboxBacklog(
                        () -> {
                            throw new IllegalStateException("connection refused");
                        },
                        clock)
                .bindTo(meters);

        assertThat(meters.get(OutboxBacklog.PENDING).gauge().value()).isNaN();
    }

    @Test
    void refusesASchemaThatIsNotAName() {
        assertThatThrownBy(() -> OutboxBacklog.of(new JdbcTemplate(), "events; drop table x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
