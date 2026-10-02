package ca.northline.privacy.application;

import ca.northline.privacy.application.PrivacyRequests.Actor;
import ca.northline.privacy.application.RetentionCatalogue.Action;
import ca.northline.privacy.application.RetentionCatalogue.Enforcement;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** S-107 retention jobs' use cases and what the console shows of them. */
public final class Retention {

    private Retention() {}

    /** The nightly run (RetentionScheduler). */
    public interface Work {

        /** Runs every category that hasn't run since the last scheduled time; returns how many categories ran. */
        int runScheduled();
    }

    /** The console's retention report (screen {@code privacy}) and its runs (action {@code privacy}). */
    public interface Desk {

        Report report(Locale locale);

        /** The report as CSV, one category per line (the console's export). */
        String csv(Locale locale);

        /** Runs (or dry-runs) one category, or every category, now. */
        List<RunView> run(Command command, Actor actor);
    }

    /** @param category a catalogue code; {@code null} = every category that runs */
    public record Command(boolean dryRun, @Nullable String category) {}

    /**
     * The report.
     *
     * @param nextRunAt the next nightly run (null when nightly runs are off)
     * @param dryRunOnly nightly runs only count ({@code RETENTION_DRY_RUN})
     * @param laws what each privacy law adds after a decision (region.privacy_laws)
     */
    public record Report(
            Instant generatedAt,
            @Nullable Instant nextRunAt,
            boolean dryRunOnly,
            List<CategoryView> categories,
            List<OperationalView> operational,
            List<LawView> laws) {

        public Report {
            categories = List.copyOf(categories);
            operational = List.copyOf(operational);
            laws = List.copyOf(laws);
        }
    }

    /**
     * One category: what the schedule says and what the jobs did.
     *
     * @param period ISO-8601 ({@code P2Y}); null for data with no end of its own
     * @param lastRun the latest run, dry runs included
     * @param lastSuccessAt the latest real run that succeeded
     * @param rowsAffected rows changed by that run
     * @param held rows past their period kept by a legal hold, at the latest run
     * @param nextDueAt when the category runs next
     * @param overdue it runs, and hasn't succeeded for longer than allowed
     */
    public record CategoryView(
            String code,
            String module,
            String name,
            @Nullable String clause,
            @Nullable String policy,
            @Nullable String period,
            @Nullable String afterDisputeClosed,
            boolean lawMinimum,
            String starts,
            String basis,
            Action action,
            Enforcement enforcement,
            List<String> holds,
            @Nullable String note,
            @Nullable RunView lastRun,
            @Nullable Instant lastSuccessAt,
            long rowsAffected,
            long held,
            @Nullable Instant nextDueAt,
            boolean overdue) {

        public CategoryView {
            holds = List.copyOf(holds);
        }
    }

    /** One category's run. */
    public record RunView(
            String category,
            boolean dryRun,
            String trigger,
            Instant startedAt,
            Instant finishedAt,
            String outcome,
            long affected,
            long held,
            long remaining) {}

    /** A short-lived kind of technical data another job deletes. */
    public record OperationalView(String code, String name, String period, String where, String setting) {}

    /** What a privacy law keeps after a decision (S-107: region.privacy_laws.decision_retention_days). */
    public record LawView(String code, String name, int decisionRetentionDays) {}
}
