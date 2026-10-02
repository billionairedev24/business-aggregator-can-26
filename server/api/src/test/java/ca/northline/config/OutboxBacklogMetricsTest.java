package ca.northline.config;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** S-113: the api reports its outbox backlog (events.event_publication) — the platform's gauges are bound. */
class OutboxBacklogMetricsTest extends IntegrationTest {

    @Autowired
    MeterRegistry meters;

    @Test
    void theOutboxBacklogIsMeasured() {
        assertThat(meters.get("northline.events.outbox.pending").gauge().value())
                .isNotNegative();
        assertThat(meters.get("northline.events.outbox.oldest_age").gauge().value())
                .isNotNegative();
    }
}
