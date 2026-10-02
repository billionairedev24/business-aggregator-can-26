package ca.northline.privacy.application;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Outbound port: {@code privacy.retention_runs} (V295) and what the jobs need of {@code privacy.requests}. */
public interface RetentionRunStore {

    void insert(RunRecord run);

    /** The latest run of each category, dry runs included. */
    Map<String, RunRecord> latest();

    /** The latest successful real (not dry) run of each category. */
    Map<String, RunRecord> latestSucceeded();

    /** When the first run of any category started; empty before the first run. */
    Optional<Instant> firstRun();

    /** True when a real scheduled run of the category succeeded at or after {@code since}. */
    boolean ranSince(String category, Instant since);

    /**
     * Takes the category's lock for the current transaction ({@code pg_try_advisory_xact_lock}); false when another
     * replica holds it.
     */
    boolean tryLock(String category);

    /** People with a privacy request still open: their data stays as it is until it ends. */
    Set<String> openRequestSubjects();

    /** A category's run. */
    record RunRecord(
            String id,
            String category,
            String module,
            boolean dryRun,
            String trigger,
            String actorId,
            Instant startedAt,
            Instant finishedAt,
            String outcome,
            long affected,
            long held,
            long remaining,
            @Nullable String error) {

        public static final String SUCCEEDED = "succeeded";
        public static final String FAILED = "failed";
        public static final String SCHEDULE = "schedule";
        public static final String STAFF = "staff";
    }
}
