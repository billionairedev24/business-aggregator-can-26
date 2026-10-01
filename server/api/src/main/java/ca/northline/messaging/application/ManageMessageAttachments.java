package ca.northline.messaging.application;

import ca.northline.messaging.application.BrowseInbox.Attachment;
import ca.northline.shared.Bytes;
import java.util.Optional;

/** Upload a file for a message or a help case (JPG, PNG, HEIC or PDF, ≤ 10 MB) and read it back. */
public interface ManageMessageAttachments {

    record Upload(String merchantId, String userId, String fileName, String contentType, Bytes bytes) {}

    record Content(Bytes bytes, String contentType, String fileName) {}

    Attachment upload(Upload upload);

    Optional<Content> content(String merchantId, String attachmentId);
}
