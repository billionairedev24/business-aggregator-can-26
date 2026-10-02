package ca.northline.privacy.application;

import ca.northline.privacy.application.PrivacyRequestStore.Kept;
import ca.northline.privacy.domain.Decision;
import ca.northline.privacy.domain.ExtensionReason;
import ca.northline.privacy.domain.PrivacyRules.Correction;
import ca.northline.privacy.domain.RequestState;
import ca.northline.privacy.domain.RequestType;
import ca.northline.privacy.domain.SubjectKind;
import ca.northline.privacy.domain.Verification;
import ca.northline.region.api.PrivacyLaw;
import ca.northline.shared.Bytes;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Privacy requests' use cases (S-105) and what they show. */
public final class PrivacyRequests {

    private PrivacyRequests() {}

    /** A request as the person and staff see it; texts in the caller's language. */
    public record RequestView(
            String id,
            String reference,
            RequestType type,
            RequestState state,
            SubjectKind subjectKind,
            String channel,
            String province,
            LawView law,
            Instant receivedAt,
            Instant dueAt,
            @Nullable Instant extendedTo,
            @Nullable ExtensionReason extensionReason,
            boolean overdue,
            @Nullable Verification verification,
            @Nullable Instant verifiedAt,
            @Nullable String codeSentTo,
            @Nullable Instant scheduledFor,
            @Nullable Instant startedAt,
            @Nullable Instant completedAt,
            @Nullable Decision decision,
            @Nullable String decisionNote,
            @Nullable ExportView export,
            List<Correction> corrections,
            @Nullable String note,
            List<StepView> steps,
            int holdsOpen) {

        public RequestView {
            corrections = List.copyOf(corrections);
            steps = List.copyOf(steps);
        }
    }

    /**
     * The law the request is handled under (region model).
     *
     * @param responseDays / {@code businessDays} / {@code extensionDays}: its deadlines
     */
    public record LawView(
            PrivacyLaw code,
            String name,
            String shortName,
            String authority,
            String authorityUrl,
            int responseDays,
            boolean businessDays,
            int extensionDays) {}

    /** The access export: ready to download until {@code expiresAt}. */
    public record ExportView(
            boolean ready,
            @Nullable Instant expiresAt,
            @Nullable Long bytes) {}

    /** One module's erasure progress: what is held for now, what is kept and why. */
    public record StepView(
            String module,
            String status,
            int attempts,
            List<Kept> holds,
            List<Kept> retained,
            @Nullable Instant doneAt) {

        public StepView {
            holds = List.copyOf(holds);
            retained = List.copyOf(retained);
        }
    }

    /** A row of the console's queue. */
    public record DeskItem(
            String id,
            String reference,
            RequestType type,
            RequestState state,
            String subjectId,
            String subjectName,
            SubjectKind subjectKind,
            String channel,
            String province,
            String law,
            Instant receivedAt,
            Instant dueAt,
            boolean overdue,
            int holdsOpen) {}

    /** A short-lived download link of the export: the data (JSON) and the readable summary. */
    public record Link(String url, String summaryUrl, Instant expiresAt) {}

    /** A downloaded part of the export. */
    public record Download(String fileName, String contentType, Bytes bytes) {}

    /** What the person does from their account (web, app). */
    public interface SelfService {

        List<RequestView> mine(String userId, Locale locale);

        RequestView one(String userId, String requestId, Locale locale);

        /**
         * Opens a request. With a fresh step-up proof it is verified at once; without one, a code is texted to the
         * account's verified mobile and the request waits for it.
         */
        RequestView open(Open command, Locale locale);

        /** Texts a new verification code. */
        RequestView sendCode(String userId, String requestId, Locale locale);

        /** Verifies with the texted code, or with a step-up proof when {@code code} is null. */
        RequestView verify(
                String userId, String requestId, @Nullable String code, @Nullable String proof, Locale locale);

        RequestView withdraw(String userId, String requestId, Locale locale);

        /** A new download link for a ready export (the previous one stops working). */
        Link link(String userId, String requestId);

        /** The fields people can ask to have corrected (they can't change them themselves). */
        Set<String> correctable();

        record Open(
                String userId,
                RequestType type,
                @Nullable List<Correction> corrections,
                @Nullable String note,
                @Nullable String stepUpProof) {}
    }

    /** The console's privacy queue (staff with the privacy screen). */
    public interface Desk {

        List<DeskItem> queue(boolean open, @Nullable RequestType type, Locale locale);

        RequestView detail(String requestId, Locale locale);

        /** Records a request made by email, phone or mail; staff verify the person before it proceeds. */
        RequestView record(Recorded command, Locale locale);

        RequestView verify(String requestId, Actor actor, Locale locale);

        RequestView extend(String requestId, ExtensionReason reason, Actor actor, Locale locale);

        RequestView reject(String requestId, Decision decision, @Nullable String note, Actor actor, Locale locale);

        /** Starts a verified erasure now instead of after its grace period. */
        RequestView start(String requestId, Actor actor, Locale locale);

        /** Applies the corrections staff accepted (all or some of those asked) and completes the request. */
        RequestView correct(String requestId, List<Correction> accepted, Actor actor, Locale locale);

        /** Retries failed erasure steps now. */
        RequestView retry(String requestId, Actor actor, Locale locale);

        record Recorded(
                String contact,
                RequestType type,
                @Nullable List<Correction> corrections,
                @Nullable String note,
                Actor actor) {}
    }

    /** A staff member acting, with their active console roles for the audit log. */
    public record Actor(String userId, String roles) {}

    /** The download link's target (no session: the token is the authorization). */
    public interface Downloads {

        /** {@code part} = {@code data} (JSON) or {@code summary} (text). */
        Download download(String token, String part);
    }
}
