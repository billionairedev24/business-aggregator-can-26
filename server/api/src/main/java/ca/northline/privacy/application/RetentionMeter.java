package ca.northline.privacy.application;

/** Outbound port: the retention jobs' metrics (docs/runbooks/retention.md § Metrics and alerts). */
public interface RetentionMeter {

    /** A real run of the category changed {@code rows} rows. */
    void purged(String category, String action, long rows);

    /** A run of the category ended ({@code succeeded} or {@code failed}). */
    void ran(String category, boolean dryRun, String outcome);
}
