package ca.northline.standing.domain;

import ca.northline.shared.CodedEnum;
import ca.northline.shared.Conflict;
import java.time.Instant;
import java.util.List;
import lombok.With;
import org.jspecify.annotations.Nullable;

/**
 * An appeal ({@code standing.appeals}, aggregate): a business contests a dispute decision or a consequence, once.
 * Staff take it (in review) and decide with a reason; whoever took the original decision can't decide it.
 *
 * @param subjectKind a dispute's decision ({@code full_refund}, {@code partial}) or the consequence's kind
 * @param originalActor who took the appealed decision ({@code system} for the rules), or null when unknown
 */
@With
public record Appeal(
        String id,
        String number,
        String merchantId,
        Subject subjectType,
        String subjectId,
        String subjectKind,
        @Nullable String originalActor,
        String statement,
        State state,
        String filedBy,
        Instant filedAt,
        @Nullable String assigneeId,
        @Nullable Instant assignedAt,
        @Nullable String decisionReason,
        @Nullable String decidedBy,
        @Nullable String decidedRole,
        @Nullable Instant decidedAt,
        long compensationCents,
        List<AppealFile> files) {

    public enum Subject implements CodedEnum {
        DISPUTE,
        CONSEQUENCE
    }

    public enum State implements CodedEnum {
        OPEN,
        IN_REVIEW,
        /** Reversed: the consequence is lifted, or Northline paid the seller. */
        GRANTED,
        /** The decision stands. */
        DENIED;

        public boolean decided() {
            return this == GRANTED || this == DENIED;
        }
    }

    public Appeal {
        files = List.copyOf(files);
    }

    public static Appeal file(
            String id,
            String number,
            String merchantId,
            Subject subject,
            String subjectId,
            String subjectKind,
            @Nullable String originalActor,
            String statement,
            String userId,
            Instant at) {
        return new Appeal(
                id,
                number,
                merchantId,
                subject,
                subjectId,
                subjectKind,
                originalActor,
                AppealRules.statement(statement),
                State.OPEN,
                userId,
                at,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                List.of());
    }

    public boolean waiting() {
        return !state.decided();
    }

    /** "Take": assigned to {@code staffId}, in review. */
    public Appeal assign(String staffId, Instant at) {
        requireWaiting();
        return withAssigneeId(staffId).withAssignedAt(at).withState(State.IN_REVIEW);
    }

    /** Granted or denied with the reason the business sees; never by whoever took the original decision. */
    public Appeal decide(boolean grant, String reason, String staffId, String role, Instant at) {
        requireWaiting();
        if (staffId.equals(originalActor)) {
            throw new Conflict("appeal_self", AppealRules.SELF);
        }
        var assigned = assigneeId == null ? withAssigneeId(staffId).withAssignedAt(at) : this;
        return assigned.withState(grant ? State.GRANTED : State.DENIED)
                .withDecisionReason(AppealRules.reason(reason))
                .withDecidedBy(staffId)
                .withDecidedRole(role)
                .withDecidedAt(at);
    }

    public Appeal attach(AppealFile file) {
        if (state != State.OPEN && state != State.IN_REVIEW) {
            throw new Conflict("appeal_not_open", AppealRules.NOT_OPEN);
        }
        if (files.size() >= AppealRules.FILES_MAX) {
            throw ca.northline.shared.RuleViolation.of("file", "count", AppealRules.FILE_COUNT);
        }
        var more = new java.util.ArrayList<>(files);
        more.add(file);
        return withFiles(more);
    }

    private void requireWaiting() {
        if (state.decided()) {
            throw new Conflict("appeal_decided", AppealRules.DECIDED);
        }
    }

    /** A file the business attached (object storage key under {@code standing/}). */
    public record AppealFile(
            String id, String name, String contentType, long size, String storageKey, Instant createdAt) {}
}
