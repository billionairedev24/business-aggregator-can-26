package ca.northline.messaging.application;

import static ca.northline.messaging.domain.MessagingRules.FILES_MAX;
import static ca.northline.messaging.domain.MessagingRules.FILE_GONE;
import static ca.northline.messaging.domain.MessagingRules.MESSAGE_REQUIRED;
import static ca.northline.messaging.domain.MessagingRules.TOO_MANY_FILES;

import ca.northline.messaging.application.AttachmentStore.StoredAttachment;
import ca.northline.messaging.application.BrowseInbox.Attachment;
import ca.northline.messaging.application.BrowseInbox.Message;
import ca.northline.messaging.application.ThreadStore.NewMessage;
import ca.northline.shared.RuleViolation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Rules shared by the Messages composer and case replies: files belong to the business, text or files required. */
final class MessageDrafts {

    private MessageDrafts() {}

    static List<String> attachments(AttachmentStore store, String merchantId, List<String> ids) {
        var distinct = ids.stream().distinct().toList();
        if (distinct.size() > FILES_MAX) {
            throw RuleViolation.of("attachmentIds", "length", TOO_MANY_FILES);
        }
        if (!distinct.isEmpty() && store.existing(merchantId, distinct).size() != distinct.size()) {
            throw RuleViolation.of("attachmentIds", "exists", FILE_GONE);
        }
        return distinct;
    }

    static String requireContent(@Nullable String body, List<String> attachmentIds) {
        var text = Objects.requireNonNullElse(body, "").strip();
        if (text.isEmpty() && attachmentIds.isEmpty()) {
            throw RuleViolation.of("body", "required", MESSAGE_REQUIRED);
        }
        return text;
    }

    static Message view(NewMessage m, AttachmentStore store, String merchantId) {
        List<Attachment> files = m.attachmentIds().stream()
                .map(id -> store.find(merchantId, id).map(StoredAttachment::view))
                .flatMap(Optional::stream)
                .toList();
        return new Message(
                m.id(), m.senderRole(), m.senderName(), m.body(), files, m.at(), m.flagged(), m.templateKey());
    }
}
