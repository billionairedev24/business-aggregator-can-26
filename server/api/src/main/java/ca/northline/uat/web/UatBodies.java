package ca.northline.uat.web;

import ca.northline.uat.domain.FeedbackRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Request bodies of the UAT endpoints (participants' and staff's). */
final class UatBodies {

    private UatBodies() {}

    static final String APPS = "studio|consumer|console|mobile|courier";
    static final String CATEGORIES = "bug|confusing|idea|praise";
    static final String SEVERITIES = "blocker|major|minor|cosmetic";
    static final String STATES = "new|triaged|accepted|fixed|verified|closed|wont_fix|duplicate";
    static final String PERSONAS = "provider|seller|kitchen|customer|courier|staff";

    /**
     * @param route the screen the participant was on (path; the server drops the query string, fragment and tokens)
     * @param platform browser and operating system ("Firefox 131 · macOS 15")
     * @param screenshotId from {@code POST /api/v1/me/pilot/screenshots}
     */
    record FeedbackBody(
            @Nullable String merchantId,

            @NotBlank(message = FeedbackRules.APP_REQUIRED)
            @Pattern(regexp = APPS, message = FeedbackRules.APP_REQUIRED)
            String app,

            @NotBlank(message = FeedbackRules.CATEGORY_REQUIRED)
            @Pattern(regexp = CATEGORIES, message = FeedbackRules.CATEGORY_REQUIRED)
            String category,

            @NotBlank(message = FeedbackRules.SEVERITY_REQUIRED)
            @Pattern(regexp = SEVERITIES, message = FeedbackRules.SEVERITY_REQUIRED)
            String severity,

            @NotBlank(message = FeedbackRules.BODY_REQUIRED)
            @Size(max = FeedbackRules.BODY_MAX, message = FeedbackRules.BODY_REQUIRED)
            String body,

            @NotBlank(message = FeedbackRules.CONTEXT_REQUIRED)
            @Size(max = 2000, message = FeedbackRules.CONTEXT_REQUIRED)
            String route,

            @NotBlank(message = FeedbackRules.CONTEXT_REQUIRED)
            @Pattern(regexp = "[A-Za-z0-9._+-]{1,40}", message = FeedbackRules.CONTEXT_REQUIRED)
            String appVersion,

            @NotBlank(message = FeedbackRules.CONTEXT_REQUIRED)
            @Pattern(regexp = "[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,2}", message = FeedbackRules.CONTEXT_REQUIRED)
            String locale,

            @NotBlank(message = FeedbackRules.CONTEXT_REQUIRED)
            @Size(max = 500, message = FeedbackRules.CONTEXT_REQUIRED)
            String platform,

            @Nullable String screenshotId) {}

    /**
     * @param blocking required when {@code to} is {@code accepted}
     * @param duplicateOf the item it repeats: required when it is marked a duplicate
     */
    record MoveBody(
            @NotBlank(message = FeedbackRules.STATE_REQUIRED)
            @Pattern(regexp = STATES, message = FeedbackRules.STATE_REQUIRED)
            String to,

            @Nullable Boolean blocking,
            @Nullable String duplicateOf,

            @Size(max = FeedbackRules.NOTE_MAX, message = FeedbackRules.NOTE_TOO_LONG) @Nullable
            String note) {}

    /** @param ownerId a staff member from {@code GET …/owners}; null = nobody */
    record OwnerBody(@Nullable String ownerId) {}

    /** @param url the tracker issue's address; null or blank removes the link */
    record TrackerBody(
            @Size(max = FeedbackRules.TRACKER_MAX, message = FeedbackRules.TRACKER_FORMAT) @Nullable
            String url) {}

    /**
     * @param contact a person's email or mobile number (customer, courier, staff)
     * @param merchantId a business's id (provider, seller, kitchen)
     */
    record ParticipantBody(
            @NotBlank(message = FeedbackRules.PERSONA_REQUIRED)
            @Pattern(regexp = PERSONAS, message = FeedbackRules.PERSONA_REQUIRED)
            String persona,

            @NotBlank(message = FeedbackRules.LABEL_REQUIRED)
            @Size(max = FeedbackRules.LABEL_MAX, message = FeedbackRules.LABEL_REQUIRED)
            String label,

            @Nullable String contact,
            @Nullable String merchantId) {}

    record SignoffBody(
            @NotBlank(message = FeedbackRules.SCRIPT_REQUIRED)
            String script,

            @NotBlank(message = FeedbackRules.OUTCOME_REQUIRED)
            @Pattern(regexp = "signed_off|with_comments|blocked", message = FeedbackRules.OUTCOME_REQUIRED)
            String outcome,

            @Size(max = FeedbackRules.COMMENTS_MAX, message = FeedbackRules.COMMENTS_TOO_LONG) @Nullable
            String comments,

            @Size(max = 50, message = FeedbackRules.BLOCKING_UNKNOWN) @Nullable
            List<@NotNull String> blockingIds) {}
}
