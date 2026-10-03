package ca.northline.uat.application;

import ca.northline.shared.Bytes;
import ca.northline.uat.domain.FeedbackState;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Inbound port: the console's UAT screen (S-121) — the feedback queue and its triage, the participants and their
 * sign-offs. Every change is in the platform audit log with the staff member's active roles.
 */
public interface UatTriage {

    List<QueueItem> queue(Filter filter);

    String csv(Filter filter, Locale locale);

    FeedbackDetail detail(String id);

    /** The screenshot's bytes and type, when it has one. */
    Optional<Screenshot> screenshot(String id);

    FeedbackDetail move(String id, Move move, Actor actor);

    FeedbackDetail assign(String id, @Nullable String ownerId, Actor actor);

    FeedbackDetail link(String id, @Nullable String trackerUrl, Actor actor);

    /** Staff who can own an item: those whose roles open the UAT screen. */
    List<Owner> owners();

    List<ParticipantView> participants(Locale locale);

    ParticipantView addParticipant(NewParticipant participant, Actor actor, Locale locale);

    ParticipantView deactivate(String participantId, Actor actor, Locale locale);

    ParticipantView signoff(String participantId, NewSignoff signoff, Actor actor, Locale locale);

    List<ScriptView> scripts(Locale locale);

    /** @param role the console role(s) acted with, for the audit log */
    record Actor(String userId, String role) {}

    /** @param state a state, or {@code open} (new, triaged, accepted, fixed) / {@code all} */
    record Filter(
            String state,
            @Nullable Boolean blocking,
            @Nullable String persona,
            @Nullable String app) {}

    /**
     * @param blocking whether it blocks the launch: required when moving to {@code accepted}
     * @param duplicateOf the item it repeats: required when it is marked a duplicate
     */
    record Move(
            FeedbackState to,
            @Nullable Boolean blocking,
            @Nullable String duplicateOf,
            @Nullable String note) {}

    record QueueItem(
            String id,
            String reference,
            String state,
            @Nullable Boolean blocking,
            String category,
            String severity,
            String persona,
            String participant,
            String app,
            String summary,
            String route,
            @Nullable String ownerId,
            @Nullable String ownerName,
            @Nullable String trackerUrl,
            @Nullable String duplicateOf,
            int duplicates,
            boolean screenshot,
            Instant createdAt,
            Instant updatedAt) {}

    record FeedbackDetail(
            QueueItem item,
            String body,
            String appVersion,
            String locale,
            String platform,
            @Nullable String merchantId,
            List<String> next,
            List<QueueItem> duplicates,
            @Nullable QueueItem duplicateOfItem,
            List<TriageStep> history) {

        public FeedbackDetail {
            next = List.copyOf(next);
            duplicates = List.copyOf(duplicates);
            history = List.copyOf(history);
        }
    }

    record TriageStep(
            @Nullable String from,
            String to,
            @Nullable Boolean blocking,
            String actorId,
            @Nullable String actorName,
            @Nullable String note,
            Instant at) {}

    record Screenshot(Bytes bytes, String contentType) {}

    record Owner(String id, String name) {}

    /**
     * @param who {@code user} or {@code business}
     * @param signoffs one per script of the persona (the latest), pending when none
     */
    record ParticipantView(
            String id,
            String persona,
            String label,
            String who,
            @Nullable String merchantId,
            boolean active,
            Instant since,
            int feedbackCount,
            List<SignoffView> signoffs) {

        public ParticipantView {
            signoffs = List.copyOf(signoffs);
        }
    }

    /** @param outcome {@code signed_off | with_comments | blocked | pending} */
    record SignoffView(
            String script,
            String scriptTitle,
            String scriptVersion,
            String outcome,
            @Nullable String comments,
            List<String> blockingRefs,
            @Nullable String recordedByName,
            @Nullable Instant recordedAt,
            int history) {

        public SignoffView {
            blockingRefs = List.copyOf(blockingRefs);
        }
    }

    /**
     * A person (customer, courier, staff). Pilot businesses take part through S-120's cohort, not here.
     *
     * @param contact the email or mobile number of their Northline account
     */
    record NewParticipant(
            String persona, String label, @Nullable String contact) {}

    /** @param blockingIds UAT feedback ids the participant named */
    record NewSignoff(
            String script, String outcome, @Nullable String comments, List<String> blockingIds) {
        public NewSignoff {
            blockingIds = List.copyOf(blockingIds);
        }
    }

    record ScriptView(String code, String persona, String title, String version, String docPath, String formPath) {}
}
