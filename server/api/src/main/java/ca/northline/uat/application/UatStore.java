package ca.northline.uat.application;

import ca.northline.uat.domain.FeedbackApp;
import ca.northline.uat.domain.FeedbackCategory;
import ca.northline.uat.domain.FeedbackState;
import ca.northline.uat.domain.Persona;
import ca.northline.uat.domain.Severity;
import ca.northline.uat.domain.SignoffOutcome;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.With;
import org.jspecify.annotations.Nullable;

/** Outbound port: schema {@code uat} (V325). */
public interface UatStore {

    // participants

    /** Active participant rows of the person, and of the businesses given (the caller checked membership). */
    List<Participant> activeFor(String userId, Collection<String> merchantIds);

    List<Participant> participants();

    Optional<Participant> participant(String id);

    /** False when the person or business already takes part with this persona. */
    boolean insert(Participant participant);

    void deactivate(String id);

    // screenshots

    void insert(Screenshot screenshot);

    Optional<Screenshot> screenshot(String userId, String id);

    /** Removes the row (the feedback took the object over, or it expired). */
    void forgetScreenshot(String id);

    /** The person's screenshots uploaded before {@code before} and never sent. */
    List<Screenshot> unsentScreenshots(String userId, Instant before);

    // feedback

    /** Inserts it; returns it with its number. */
    Feedback insert(Feedback feedback);

    Optional<Feedback> feedback(String id);

    /** The item, locked until the transaction ends. */
    Optional<Feedback> lock(String id);

    /** Saves the triage columns when the version still matches; false when someone else saved first. */
    boolean save(Feedback feedback);

    List<Feedback> feedbackOf(String userId);

    /** The queue, newest first; every item when {@code states} is empty. */
    List<Feedback> queue(Collection<FeedbackState> states, @Nullable Boolean blocking, int limit);

    List<Feedback> duplicatesOf(String id);

    /** How many duplicates each of these items has. */
    Map<String, Integer> duplicateCounts(Collection<String> ids);

    void append(HistoryEntry entry);

    List<HistoryEntry> history(String feedbackId);

    /** Every triage step, oldest first (the go/no-go trend replays them). */
    List<HistoryEntry> allHistory();

    /** When each item was reported, for the trend. */
    List<Instant> reportedSince(Instant since);

    // scripts and sign-offs

    List<Script> scripts();

    void insert(Signoff signoff);

    /** The latest sign-off per participant and script. */
    List<Signoff> latestSignoffs();

    List<Signoff> signoffsOf(String participantId);

    /** S-105: the person's data in this schema. */
    record Participant(
            String id,
            @Nullable String userId,
            @Nullable String merchantId,
            Persona persona,
            String label,
            boolean active,
            String addedBy,
            Instant createdAt) {}

    record Screenshot(
            String id, String userId, String storageKey, String contentType, int byteSize, Instant createdAt) {}

    @With
    record Feedback(
            String id,
            long number,
            String participantId,
            Persona persona,
            String participantLabel,
            String userId,
            @Nullable String merchantId,
            FeedbackApp app,
            FeedbackCategory category,
            Severity severity,
            String body,
            String route,
            String appVersion,
            String locale,
            String platform,
            @Nullable String screenshotKey,
            @Nullable String screenshotType,
            @Nullable Integer screenshotBytes,
            FeedbackState state,
            @Nullable Boolean blocking,
            @Nullable String ownerId,
            @Nullable String trackerUrl,
            @Nullable String duplicateOf,
            Instant createdAt,
            Instant updatedAt,
            int version) {

        public boolean blocks() {
            return Boolean.TRUE.equals(blocking);
        }
    }

    record HistoryEntry(
            String id,
            String feedbackId,
            @Nullable FeedbackState from,
            FeedbackState to,
            @Nullable Boolean blocking,
            String actorId,
            @Nullable String note,
            Instant at) {}

    record Script(String code, Persona persona, String version, String titleEn, String titleFr, String docPath) {}

    record Signoff(
            String id,
            String participantId,
            String scriptCode,
            String scriptVersion,
            SignoffOutcome outcome,
            @Nullable String comments,
            List<String> blockingIds,
            String recordedBy,
            Instant recordedAt) {

        public Signoff {
            blockingIds = List.copyOf(blockingIds);
        }
    }
}
