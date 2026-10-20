package ca.northline.standing.application;

import ca.northline.shared.Bytes;
import ca.northline.shared.MerchantScope;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** The standing module's use cases (S-156), one interface each, with the views they answer. */
public final class StandingUseCases {

    private StandingUseCases() {}

    /** Studio › Standing, the consumer's account, and the console's view of a business. */
    public interface ViewStanding {

        BusinessStanding business(String merchantId);

        CustomerStanding customer(String userId);
    }

    /** Studio: file an appeal (owners), add its files, read them back. */
    public interface FileAppeal {

        /** @param subjectType {@code dispute} | {@code consequence} */
        AppealView file(String merchantId, String subjectType, String subjectId, String statement, String userId);

        AppealView attach(String merchantId, String appealId, Upload upload);

        Optional<StoredFile> file(@Nullable String merchantId, String appealId, String fileId);
    }

    /** Console › Trust &amp; safety › Appeals: the queue, take, decide (audited, emailed). */
    public interface AppealDesk {

        List<QueueItem> queue(MerchantScope scope);

        Optional<AppealDetail> detail(String appealId);

        AppealDetail assign(String appealId, String staffId, String role);

        AppealDetail decide(String appealId, boolean grant, String reason, String staffId, String role);
    }

    /** Console › seller detail: lift a consequence (the reason goes to the business), start or close coaching. */
    public interface ManageStanding {

        ConsequenceView lift(String consequenceId, String reason, String staffId, String role);

        CoachingView startCoaching(
                String merchantId,
                @Nullable String title,
                @Nullable String note,
                @Nullable LocalDate dueOn,
                @Nullable List<String> tasks,
                String staffId,
                String role);

        CoachingView closeCoaching(
                String planId, boolean completed, @Nullable String reason, String staffId, String role);
    }

    /** Studio: tick a coaching task off (or back on). */
    public interface TickCoachingTask {

        CoachingView tick(String merchantId, String planId, String taskId, boolean done, String userId);
    }

    /** The nightly job: S-93's no-show rules, and photo delays that ran out. */
    public interface EnforceStanding {

        Result run();

        record Result(int instantBookPaused, int instantBookResumed, int customersWarned, int customersCleared) {}
    }

    public record Upload(String name, String contentType, Bytes bytes) {}

    public record StoredFile(Bytes bytes, String contentType, String name) {}

    /**
     * @param liftedByStaff lifted by a person (or a granted appeal), not by the rule
     * @param appealable the business can still appeal it; {@code appealBy} is the last moment
     */
    public record ConsequenceView(
            String id,
            String kind,
            String cause,
            String reason,
            Map<String, String> detail,
            Instant startedAt,
            @Nullable Instant endsAt,
            boolean active,
            @Nullable Instant liftedAt,
            @Nullable String liftReason,
            boolean liftedByStaff,
            boolean appealable,
            @Nullable Instant appealBy,
            @Nullable AppealView appeal) {

        public ConsequenceView {
            detail = Map.copyOf(detail);
        }
    }

    /**
     * @param subjectLabel a dispute's case number ({@code DS-1188}); null for a consequence
     * @param state {@code open} | {@code in_review} | {@code granted} | {@code denied}
     */
    public record AppealView(
            String id,
            String number,
            String subjectType,
            String subjectId,
            String subjectKind,
            @Nullable String subjectLabel,
            String statement,
            String state,
            Instant filedAt,
            @Nullable Instant decidedAt,
            @Nullable String decisionReason,
            long compensationCents,
            List<FileView> files) {

        public AppealView {
            files = List.copyOf(files);
        }
    }

    public record FileView(String id, String name, String contentType, long size) {}

    public record CoachingView(
            String id,
            String title,
            @Nullable String note,
            @Nullable LocalDate dueOn,
            String state,
            Instant createdAt,
            @Nullable Instant closedAt,
            @Nullable String closeReason,
            List<TaskView> tasks) {

        public CoachingView {
            tasks = List.copyOf(tasks);
        }
    }

    public record TaskView(
            String id, String title, boolean done, @Nullable Instant doneAt) {}

    /** A dispute decision the business may still appeal. */
    public record DisputeView(
            String disputeId,
            String caseNumber,
            String subject,
            String decision,
            long refundCents,
            Instant decidedAt,
            Instant appealBy,
            @Nullable String note) {}

    public record BusinessStanding(
            List<ConsequenceView> active,
            List<ConsequenceView> past,
            List<AppealView> appeals,
            List<DisputeView> appealableDisputes,
            @Nullable CoachingView coaching,
            List<CoachingView> pastCoaching) {

        public BusinessStanding {
            active = List.copyOf(active);
            past = List.copyOf(past);
            appeals = List.copyOf(appeals);
            appealableDisputes = List.copyOf(appealableDisputes);
            pastCoaching = List.copyOf(pastCoaching);
        }
    }

    public record CustomerStanding(List<ConsequenceView> active, List<ConsequenceView> past) {
        public CustomerStanding {
            active = List.copyOf(active);
            past = List.copyOf(past);
        }
    }

    /** A queue row: the appeal with its business and who has it. */
    public record QueueItem(
            AppealView appeal,
            String merchantId,
            String businessName,
            @Nullable String province,
            @Nullable String assigneeId,
            @Nullable String assigneeName) {}

    /**
     * @param consequence the appealed consequence, when it is one
     * @param dispute the appealed dispute's facts, when it is one
     * @param originalActorName who took the appealed decision ("the trust rules" for {@code system})
     */
    public record AppealDetail(
            QueueItem item,
            @Nullable ConsequenceView consequence,
            @Nullable DisputeView dispute,
            @Nullable String originalActor,
            @Nullable String originalActorName,
            @Nullable String decidedBy,
            @Nullable String decidedByName) {}
}
