package ca.northline.messaging.application;

import static ca.northline.messaging.domain.MessagingRules.FILE_MAX_BYTES;
import static ca.northline.messaging.domain.MessagingRules.FILE_REQUIRED;
import static ca.northline.messaging.domain.MessagingRules.FILE_TOO_LARGE;
import static ca.northline.messaging.domain.MessagingRules.FILE_TYPE;
import static ca.northline.messaging.domain.MessagingRules.FILE_TYPES;

import ca.northline.messaging.application.AttachmentStore.StoredAttachment;
import ca.northline.messaging.application.BrowseInbox.Attachment;
import ca.northline.shared.Ids;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Attachments for messages and help cases: JPG, PNG, HEIC or PDF up to 10 MB, stored under the business's prefix. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class MessageAttachmentService implements ManageMessageAttachments {

    private final AttachmentStore store;
    private final AttachmentStorage storage;
    private final Clock clock;

    @Override
    @Transactional
    public Attachment upload(Upload upload) {
        if (upload.bytes().length == 0) {
            throw RuleViolation.of("file", "required", FILE_REQUIRED);
        }
        var type = upload.contentType().toLowerCase(Locale.ROOT);
        if (!FILE_TYPES.contains(type) || !signatureMatches(type, upload.bytes())) {
            throw RuleViolation.of("file", "format", FILE_TYPE);
        }
        if (upload.bytes().length > FILE_MAX_BYTES) {
            throw RuleViolation.of("file", "size", FILE_TOO_LARGE);
        }
        var id = Ids.next();
        var name = upload.fileName().isBlank() ? id : upload.fileName().strip();
        var attachment = new StoredAttachment(
                id,
                upload.merchantId(),
                "messages/%s/%s".formatted(upload.merchantId(), id),
                name.length() > 200 ? name.substring(name.length() - 200) : name,
                type,
                upload.bytes().length,
                upload.userId(),
                clock.instant());
        storage.put(attachment.storageKey(), upload.bytes(), type);
        store.insert(attachment);
        return attachment.view();
    }

    @Override
    public Optional<Content> content(String merchantId, String attachmentId) {
        return store.find(merchantId, attachmentId)
                .flatMap(a ->
                        storage.get(a.storageKey()).map(bytes -> new Content(bytes, a.contentType(), a.fileName())));
    }

    /** The declared type must match the file's magic bytes (HEIC: an ISO-BMFF {@code ftyp} box). */
    static boolean signatureMatches(String type, byte[] b) {
        return switch (type) {
            case "image/jpeg" -> b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
            case "image/png" -> b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
            case "application/pdf" -> b.length > 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F';
            case "image/heic", "image/heif" ->
                b.length > 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p';
            default -> false;
        };
    }
}
