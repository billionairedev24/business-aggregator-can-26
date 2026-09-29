package ca.northline.catalogue.domain;

import ca.northline.shared.RuleViolation.Violation;
import java.util.ArrayList;
import java.util.List;

/**
 * How far a listing is from "Submit for vetting" (editor completeness meter). {@code done}/{@code total} sections are
 * complete; {@code missing} holds one violation per missing field, returned as a 422 when submitting too early.
 */
public record Completeness(int done, int total, List<Violation> missing) {

    public Completeness {
        missing = List.copyOf(missing);
    }

    public int percent() {
        return Math.round(done * 100f / total);
    }

    /** Collects per-section results. */
    static final class Tally {
        private final List<Violation> missing = new ArrayList<>();
        private int done;
        private int total;

        /** A section that is always complete (the editor's Preview tab). */
        Tally always() {
            total++;
            done++;
            return this;
        }

        /** A section, complete when none of its checks added a violation. */
        Tally section(List<Violation> problems) {
            total++;
            if (problems.isEmpty()) {
                done++;
            }
            missing.addAll(problems);
            return this;
        }

        Completeness result() {
            return new Completeness(done, total, missing);
        }
    }
}
