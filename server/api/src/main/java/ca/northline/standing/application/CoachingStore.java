package ca.northline.standing.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: {@code standing.coaching_plans} and {@code coaching_tasks}. */
public interface CoachingStore {

    /** False when the business already has an active plan. */
    boolean insert(Plan plan);

    Optional<Plan> find(String planId);

    Optional<Plan> active(String merchantId);

    /** Newest first. */
    List<Plan> ofBusiness(String merchantId, int limit);

    /** @param state {@code completed} | {@code cancelled} */
    void close(String planId, String state, String by, @Nullable String reason, Instant at);

    /** False when the task isn't part of the plan. */
    boolean tick(String planId, String taskId, boolean done, String by, Instant at);

    /**
     * @param state {@code active} | {@code completed} | {@code cancelled}
     */
    record Plan(
            String id,
            String merchantId,
            String title,
            @Nullable String note,
            @Nullable LocalDate dueOn,
            String state,
            String createdBy,
            String createdRole,
            Instant createdAt,
            @Nullable Instant closedAt,
            @Nullable String closeReason,
            List<Task> tasks) {

        public Plan {
            tasks = List.copyOf(tasks);
        }
    }

    record Task(
            String id,
            int sort,
            String title,
            @Nullable Instant doneAt,
            @Nullable String doneBy) {}
}
