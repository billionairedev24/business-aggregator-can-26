package ca.northline.golive.adapters;

import ca.northline.uat.api.UatReadiness;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * S-118 hypercare: the pilot group's UAT feedback (S-121) as gauges for the hypercare dashboard —
 * {@code northline.uat.feedback.reported} (sent today, the platform's day) and {@code northline.uat.blocking.open}. The
 * report is read at most every 5 minutes, whatever the scrape interval; a failed read keeps the last values.
 */
@Slf4j
@Component
class UatFeedbackGauges {

    private static final Duration EVERY = Duration.ofMinutes(5);

    private final UatReadiness uat;
    private final Clock clock;
    private final AtomicReference<@Nullable Snapshot> last = new AtomicReference<>();

    private record Snapshot(Instant at, double reported, double blocking) {}

    UatFeedbackGauges(UatReadiness uat, Clock clock, MeterRegistry meters) {
        this.uat = uat;
        this.clock = clock;
        Gauge.builder("northline.uat.feedback.reported", this, g -> g.snapshot().reported())
                .register(meters);
        Gauge.builder("northline.uat.blocking.open", this, g -> g.snapshot().blocking())
                .register(meters);
    }

    private Snapshot snapshot() {
        var now = clock.instant();
        var current = last.get();
        if (current != null && current.at().plus(EVERY).isAfter(now)) {
            return current;
        }
        try {
            var report = uat.report(Locale.CANADA);
            var today = report.trend().isEmpty() ? 0 : report.trend().getLast().reported();
            var fresh = new Snapshot(now, today, report.blockingOpen() + report.blockingUnverified());
            last.set(fresh);
            return fresh;
        } catch (RuntimeException e) {
            log.debug("UAT gauges: the report couldn't be read: {}", e.toString());
            return current != null ? current : new Snapshot(now, Double.NaN, Double.NaN);
        }
    }
}
