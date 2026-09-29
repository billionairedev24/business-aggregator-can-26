package ca.northline.messaging.web;

import static ca.northline.messaging.domain.MessagingRules.CASE_BODY_MAX;
import static ca.northline.messaging.domain.MessagingRules.CASE_BODY_REQUIRED;
import static ca.northline.messaging.domain.MessagingRules.CASE_BODY_TOO_LONG;
import static ca.northline.messaging.domain.MessagingRules.CHANNEL_CODES;
import static ca.northline.messaging.domain.MessagingRules.CHANNEL_REQUIRED;
import static ca.northline.messaging.domain.MessagingRules.FILES_MAX;
import static ca.northline.messaging.domain.MessagingRules.MESSAGE_MAX;
import static ca.northline.messaging.domain.MessagingRules.MESSAGE_TOO_LONG;
import static ca.northline.messaging.domain.MessagingRules.REF_TYPE_CODES;
import static ca.northline.messaging.domain.MessagingRules.RELATED_INVALID;
import static ca.northline.messaging.domain.MessagingRules.TOO_MANY_FILES;
import static ca.northline.messaging.domain.MessagingRules.TOPIC_CODES;
import static ca.northline.messaging.domain.MessagingRules.TOPIC_REQUIRED;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Request bodies of the Messages and Help endpoints, with the exact messages of {@code MessagingRules}. */
final class MessagingRequests {

    private MessagingRequests() {}

    /** Composer and case replies: text and/or up to 5 attachments; {@code templateKey} when sent from a quick reply. */
    record MessageRequest(
            @Nullable @Size(max = MESSAGE_MAX, message = MESSAGE_TOO_LONG)
            String body,

            @Nullable @Size(max = FILES_MAX, message = TOO_MANY_FILES)
            List<String> attachmentIds,

            @Nullable String templateKey) {
        List<String> files() {
            return Objects.requireNonNullElse(attachmentIds, List.of());
        }
    }

    /** Case replies may be as long as the case description. */
    record CaseReplyRequest(
            @Nullable @Size(max = CASE_BODY_MAX, message = CASE_BODY_TOO_LONG)
            String body,

            @Nullable @Size(max = FILES_MAX, message = TOO_MANY_FILES)
            List<String> attachmentIds) {
        List<String> files() {
            return Objects.requireNonNullElse(attachmentIds, List.of());
        }
    }

    /** Help › Contact support. */
    record OpenCaseRequest(
            @NotBlank(message = TOPIC_REQUIRED) @Pattern(regexp = TOPIC_CODES, message = TOPIC_REQUIRED)
            String topic,

            @Nullable @Pattern(regexp = REF_TYPE_CODES, message = RELATED_INVALID)
            String refType,

            @Nullable @Size(max = 64, message = RELATED_INVALID)
            String refId,

            @Nullable @Size(max = 200, message = RELATED_INVALID)
            String refLabel,

            @NotBlank(message = CASE_BODY_REQUIRED) @Size(max = CASE_BODY_MAX, message = CASE_BODY_TOO_LONG)
            String body,

            @Nullable @Size(max = FILES_MAX, message = TOO_MANY_FILES)
            List<String> attachmentIds,

            @NotBlank(message = CHANNEL_REQUIRED) @Pattern(regexp = CHANNEL_CODES, message = CHANNEL_REQUIRED)
            String channel,

            boolean urgent) {
        List<String> files() {
            return Objects.requireNonNullElse(attachmentIds, List.of());
        }
    }
}
