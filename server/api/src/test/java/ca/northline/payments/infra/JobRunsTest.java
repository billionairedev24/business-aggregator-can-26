package ca.northline.payments.infra;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** S-113: each payments job run is counted by outcome; the last success is a timestamp for the "stalled" alerts. */
class JobRunsTest {

    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final Instant at = Instant.parse("2026-10-02T15:00:00Z");
    final JobRuns runs = new JobRuns(meters, Clock.fixed(at, ZoneOffset.UTC));

    @Test
    void countsRunsAndRemembersTheLastSuccess() {
        runs.failed(JobRuns.PAYOUTS);
        assertThat(meters.find(JobRuns.LAST_SUCCESS).gauge()).isNull(); // none yet: no misleading 0

        runs.succeeded(JobRuns.PAYOUTS);
        runs.succeeded(JobRuns.PAYOUTS);

        assertThat(meters.get(JobRuns.RUNS)
                        .tag("job", "payments.payouts")
                        .tag("outcome", "succeeded")
                        .counter()
                        .count())
                .isEqualTo(2);
        assertThat(meters.get(JobRuns.RUNS).tag("outcome", "failed").counter().count())
                .isEqualTo(1);
        assertThat(meters.get(JobRuns.LAST_SUCCESS)
                        .tag("job", "payments.payouts")
                        .gauge()
                        .value())
                .isEqualTo((double) at.getEpochSecond());
    }
}
