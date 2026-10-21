package ca.northline.standing.domain;

import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** S-156: a coaching plan staff assign (design 03 "Coaching plan"): a title, a note, up to 10 tasks, a due date. */
public final class CoachingRules {

    public static final int TITLE_MAX = 120;
    public static final int TASK_MAX = 200;
    public static final int TASKS_MAX = 10;
    public static final int NOTE_MAX = 1000;

    public static final String TITLE_REQUIRED = "Give the plan a title.";
    public static final String TITLE_TOO_LONG = "Keep the title under 120 characters.";
    public static final String TASKS_REQUIRED = "Add at least one task.";
    public static final String TOO_MANY_TASKS = "At most 10 tasks.";
    public static final String TASK_TOO_LONG = "Keep each task under 200 characters.";
    public static final String NOTE_TOO_LONG = "Keep the note under 1,000 characters.";
    public static final String DUE_PAST = "Pick a date after today.";
    public static final String ACTIVE = "This business already has a coaching plan.";
    public static final String CLOSED = "This coaching plan is closed.";

    private CoachingRules() {}

    /** A plan's content cleaned up, or a 422 listing every field that is wrong. */
    public record Draft(
            String title, @Nullable String note, @Nullable LocalDate dueOn, List<String> tasks) {

        public Draft {
            tasks = List.copyOf(tasks);
        }

        public static Draft validate(
                @Nullable String title,
                @Nullable String note,
                @Nullable LocalDate dueOn,
                @Nullable List<String> tasks,
                LocalDate today) {
            var problems = new ArrayList<Violation>();
            var t = title == null ? "" : title.strip();
            if (t.isEmpty()) {
                problems.add(new Violation("title", "required", TITLE_REQUIRED));
            } else if (t.length() > TITLE_MAX) {
                problems.add(new Violation("title", "length", TITLE_TOO_LONG));
            }
            var n = note == null || note.isBlank() ? null : note.strip();
            if (n != null && n.length() > NOTE_MAX) {
                problems.add(new Violation("note", "length", NOTE_TOO_LONG));
            }
            if (dueOn != null && !dueOn.isAfter(today)) {
                problems.add(new Violation("dueOn", "range", DUE_PAST));
            }
            var clean = tasks == null
                    ? List.<String>of()
                    : tasks.stream()
                            .filter(x -> x != null && !x.isBlank())
                            .map(String::strip)
                            .toList();
            if (clean.isEmpty()) {
                problems.add(new Violation("tasks", "required", TASKS_REQUIRED));
            } else if (clean.size() > TASKS_MAX) {
                problems.add(new Violation("tasks", "length", TOO_MANY_TASKS));
            } else if (clean.stream().anyMatch(x -> x.length() > TASK_MAX)) {
                problems.add(new Violation("tasks", "length", TASK_TOO_LONG));
            }
            if (!problems.isEmpty()) {
                throw new RuleViolation(problems);
            }
            return new Draft(t, n, dueOn, clean);
        }
    }
}
