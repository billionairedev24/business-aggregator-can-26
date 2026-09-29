package ca.northline.messaging.application;

import ca.northline.messaging.application.BrowseInbox.Attachment;
import java.util.Optional;

/** Upload a file for a message or a help case (JPG, PNG, HEIC or PDF, ≤ 10 MB) and read it back. */
public interface ManageMessageAttachments {

    record Upload(String merchantId, String userId, String fileName, String contentType, byte[] bytes) {}

    record Content(byte[] bytes, String contentType, String fileName) {}

    Attachment upload(Upload upload);

    Optional<Content> content(String merchantId, String attachmentId);
}
