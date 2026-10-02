package ca.northline.privacy.application;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code northline.retention.*} (S-107, docs/runbooks/retention.md): when and how much the retention jobs do. The
 * periods are not here: they are the Privacy Policy's ({@link RetentionCatalogue}).
 *
 * @param enabled {@code RETENTION_ENABLED} (true): run nightly; off, only staff runs from the console happen
 * @param cron {@code RETENTION_CRON} (0 47 2 * * *): when, in the platform zone (region model)
 * @param batch {@code RETENTION_BATCH} (500): rows per transaction
 * @param maxBatches {@code RETENTION_MAX_BATCHES} (40): batches per category per run; the rest waits for the next run
 * @param dryRun {@code RETENTION_DRY_RUN} (false): nightly runs only count what they would change
 * @param staleAfter a category that hasn't run successfully for this long is overdue in the report (P2D)
 */
@ConfigurationProperties("northline.retention")
public record RetentionSettings(
        @Nullable Boolean enabled,
        @Nullable String cron,
        @Nullable Integer batch,
        @Nullable Integer maxBatches,
        @Nullable Boolean dryRun,
        @Nullable Duration staleAfter) {

    public static final String DEFAULT_CRON = "0 47 2 * * *";

    public boolean on() {
        return enabled == null || enabled;
    }

    public String schedule() {
        return cron == null || cron.isBlank() ? DEFAULT_CRON : cron;
    }

    public int batchSize() {
        return batch == null || batch < 1 ? 500 : Math.min(batch, 10_000);
    }

    public int batchLimit() {
        return maxBatches == null || maxBatches < 1 ? 40 : maxBatches;
    }

    public boolean onlyCount() {
        return dryRun != null && dryRun;
    }

    public Duration stale() {
        return staleAfter == null ? Duration.ofDays(2) : staleAfter;
    }
}
