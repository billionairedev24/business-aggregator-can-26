package ca.northline.payments.infra;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * S-113: the outcome of each scheduled payments job run, for the payout run SLO and the "job stalled" alerts
 * (docs/runbooks/alerting.md):
 *
 * <ul>
 *   <li>{@code northline.jobs.runs{job, outcome}} — runs that {@code succeeded} or {@code failed};
 *   <li>{@code northline.jobs.last_success{job}} — when the job last succeeded, in Unix seconds (Prometheus:
 *       {@code northline_jobs_last_success_seconds}); absent until the first success after start-up.
 * </ul>
 */
@Component
@RequiredArgsConstructor
class JobRuns {

    /** The scheduled payout run (and the fallback settlement of payouts in transit). */
    static final String PAYOUTS = "payments.payouts";

    static final String RUNS = "northline.jobs.runs";
    static final String LAST_SUCCESS = "northline.jobs.last_success";

    private final MeterRegistry meters;
    private final Clock clock;
    private final Map<String, AtomicLong> lastSuccess = new ConcurrentHashMap<>();

    void succeeded(String job) {
        meters.counter(RUNS, "job", job, "outcome", "succeeded").increment();
        lastSuccess
                .computeIfAbsent(job, j -> {
                    var at = new AtomicLong();
                    Gauge.builder(LAST_SUCCESS, at, AtomicLong::get)
                            .tag("job", j)
                            .baseUnit("seconds")
                            .register(meters);
                    return at;
                })
                .set(clock.instant().getEpochSecond());
    }

    void failed(String job) {
        meters.counter(RUNS, "job", job, "outcome", "failed").increment();
    }
}
