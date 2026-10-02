package ca.northline.privacy.adapters;

import ca.northline.privacy.application.RetentionCatalogue;
import ca.northline.privacy.application.RetentionMeter;
import ca.northline.privacy.application.RetentionRunStore;
import ca.northline.privacy.application.RetentionRunStore.RunRecord;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * S-107 metrics (docs/runbooks/retention.md § Metrics and alerts):
 *
 * <ul>
 *   <li>{@code northline.retention.rows{category, action}} — rows deleted, pseudonymised or aggregated by real runs;
 *   <li>{@code northline.retention.runs{category, mode, outcome}} — runs ({@code mode} = run | dry_run);
 *   <li>{@code northline.retention.last_success{category}} — when the category last ran successfully (a real run), in
 *       Unix seconds, read from {@code privacy.retention_runs} so every replica reports the same and a restart
 *       forgets nothing. Before a category's first success it reports when the first run of any category started
 *       (or this replica's start), so {@code NorthlineRetentionNotRun} catches a category that never succeeds.
 * </ul>
 */
@Component
class RetentionMetrics implements RetentionMeter {

    static final String ROWS = "northline.retention.rows";
    static final String RUNS = "northline.retention.runs";
    static final String LAST_SUCCESS = "northline.retention.last_success";

    private static final Duration CACHE = Duration.ofSeconds(60);

    private final MeterRegistry meters;
    private final RetentionRunStore store;
    private final Clock clock;
    private final Instant started;
    private final AtomicReference<@Nullable Snapshot> snapshot = new AtomicReference<>();

    private record Snapshot(Instant at, Map<String, RunRecord> succeeded, Instant baseline) {}

    RetentionMetrics(MeterRegistry meters, RetentionRunStore store, RetentionCatalogue catalogue, Clock clock) {
        this.meters = meters;
        this.store = store;
        this.clock = clock;
        this.started = clock.instant();
        for (var category : catalogue.runnable()) {
            Gauge.builder(LAST_SUCCESS, () -> lastSuccess(category.code()))
                    .tag("category", category.code())
                    .baseUnit("seconds")
                    .register(meters);
        }
    }

    @Override
    public void purged(String category, String action, long rows) {
        meters.counter(ROWS, "category", category, "action", action).increment((double) rows);
    }

    @Override
    public void ran(String category, boolean dryRun, String outcome) {
        meters.counter(RUNS, "category", category, "mode", dryRun ? "dry_run" : "run", "outcome", outcome)
                .increment();
        snapshot.set(null);
    }

    private double lastSuccess(String category) {
        try {
            var now = clock.instant();
            var current = snapshot.get();
            if (current == null || current.at().plus(CACHE).isBefore(now)) {
                current = new Snapshot(now, store.latestSucceeded(), store.firstRun().orElse(started));
                snapshot.set(current);
            }
            var success = current.succeeded().get(category);
            return (success != null ? success.finishedAt() : current.baseline()).getEpochSecond();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}
