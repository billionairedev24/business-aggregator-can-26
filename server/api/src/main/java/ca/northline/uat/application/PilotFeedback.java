package ca.northline.uat.application;

import ca.northline.shared.Bytes;
import ca.northline.uat.domain.FeedbackApp;
import ca.northline.uat.domain.FeedbackCategory;
import ca.northline.uat.domain.Severity;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Inbound port: a pilot participant's side of UAT — the feedback control in the Studio, the web, the console, the app. */
public interface PilotFeedback {

    /**
     * Whether the feedback control shows; for anyone signed in. {@code app} {@link FeedbackApp#COURIER}: only as a pilot
     * courier (the courier app's button); any other app prefers the person's other personas.
     */
    Status status(String userId, @Nullable String merchantId, @Nullable FeedbackApp app);

    /** A screenshot to attach to the next feedback (PNG/JPEG, ≤ 5 MB, S-104's checks). Participants only. */
    Uploaded screenshot(String userId, String contentType, Bytes bytes);

    /** Sends feedback; the text and context are cleaned first ({@code FeedbackRules}). Participants only. */
    Sent send(Submission submission);

    /** What the person sent, newest first, with where triage has got to. */
    List<Mine> mine(String userId);

    /** @param persona the participant's persona, null when not a participant */
    record Status(
            boolean participant, @Nullable String persona, long screenshotMaxBytes, List<String> screenshotTypes) {
        public Status {
            screenshotTypes = List.copyOf(screenshotTypes);
        }
    }

    record Uploaded(String id, String contentType, int size) {}

    record Submission(
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
            @Nullable String screenshotId) {}

    /** @param reference {@code UAT-1001} */
    record Sent(String id, String reference) {}

    record Mine(String id, String reference, String category, String state, String route, Instant sentAt) {}
}
