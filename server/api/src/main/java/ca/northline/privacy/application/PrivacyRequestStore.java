package ca.northline.privacy.application;

import ca.northline.privacy.domain.Decision;
import ca.northline.privacy.domain.ExtensionReason;
import ca.northline.privacy.domain.RequestState;
import ca.northline.privacy.domain.RequestType;
import ca.northline.privacy.domain.SubjectKind;
import ca.northline.privacy.domain.Verification;
import ca.northline.region.api.PrivacyLaw;
import ca.northline.shared.crypto.SecretSealer.Sealed;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.With;
import org.jspecify.annotations.Nullable;

/** Outbound port: {@code privacy.requests} and {@code privacy.erasure_steps} (V270). */
public interface PrivacyRequestStore {

    /** A request's sealed contact and corrections use this + the request id as context (the key re-wrap too, S-115). */
    String SEALED_CONTEXT = "privacy-request:";

    /** Inserts the request; returns it with its number. */
    Request insert(Request request);

    Optional<Request> find(String id);

    /** The request, locked until the transaction ends ({@code FOR UPDATE}). */
    Optional<Request> lock(String id);

    /** Saves the changeable columns when the version still matches; false when someone else saved first. */
    boolean save(Request request);

    boolean hasOpen(String subjectId, RequestType type);

    /** S-104: verification codes texted to this person since {@code since}, across all their requests. */
    int textsSince(String subjectId, Instant since);

    /** S-104: records a texted code (and forgets texts older than two days). */
    void textSent(String subjectId, String requestId, Instant at);

    /** The person's requests, newest first. */
    List<Request> of(String subjectId);

    /** The console queue: requests in these states (of this type), the soonest due first. */
    List<Request> queue(Set<RequestState> states, @Nullable RequestType type, int limit);

    /** Access and erasure requests whose work is due: verified and scheduled, or in progress. */
    List<String> due(Instant now, int limit);

    /** Requests with an erasure step due (pending, failed or held, its next attempt passed). */
    List<String> withDueSteps(Instant now, int limit);

    /** Requests whose export expired before {@code now} and still has a stored bundle. */
    List<Request> expiredExports(Instant now, int limit);

    Optional<Request> byLink(String linkHash);

    /** Creates the pipeline's steps (idempotent: existing ones are kept). */
    void createSteps(String requestId, Collection<StepKey> steps, Instant now);

    List<Step> steps(String requestId);

    /** The step, locked ({@code FOR UPDATE SKIP LOCKED}); empty when another worker holds it. */
    Optional<Step> lockStep(String requestId, String module);

    void saveStep(Step step);

    record StepKey(String module, int sort) {}

    /** A data subject request. */
    @With
    record Request(
            String id,
            long number,
            String subjectId,
            SubjectKind subjectKind,
            List<String> merchantIds,
            RequestType type,
            RequestState state,
            String channel,
            String province,
            PrivacyLaw law,
            Instant receivedAt,
            Instant dueAt,
            @Nullable Instant extendedTo,
            @Nullable ExtensionReason extensionReason,
            @Nullable Verification verification,
            @Nullable Instant verifiedAt,
            @Nullable String verifiedBy,
            @Nullable String codeHash,
            @Nullable Instant codeExpiresAt,
            int codeAttempts,
            @Nullable Instant scheduledFor,
            @Nullable Instant startedAt,
            @Nullable Instant completedAt,
            @Nullable Decision decision,
            @Nullable String decisionNote,
            @Nullable String decidedBy,
            @Nullable String createdBy,
            @Nullable Sealed sealed,
            @Nullable String exportKey,
            @Nullable Long exportBytes,
            @Nullable Instant exportExpiresAt,
            @Nullable String linkHash,
            @Nullable Instant linkExpiresAt,
            int holdsOpen,
            int version) {

        public Request {
            merchantIds = List.copyOf(merchantIds);
        }

        /** The deadline in force: the extended one when extended. */
        public Instant deadline() {
            return extendedTo != null ? extendedTo : dueAt;
        }

        public boolean overdue(Instant now) {
            return state.open() && now.isAfter(deadline());
        }
    }

    /** One module's progress in an erasure. */
    @With
    record Step(
            String requestId,
            String module,
            int sort,
            String status,
            int attempts,
            @Nullable String lastError,
            List<Kept> holds,
            List<Kept> retained,
            @Nullable Instant nextAttemptAt,
            @Nullable Instant doneAt) {

        public static final String PENDING = "pending";
        public static final String DONE = "done";
        public static final String HELD = "held";
        public static final String FAILED = "failed";

        public Step {
            holds = List.copyOf(holds);
            retained = List.copyOf(retained);
        }
    }

    /** A category of data kept or held, with the reason's code. */
    record Kept(String category, String reason) {}
}
