package ca.northline.messaging.application;

import ca.northline.messaging.application.BrowseInbox.Attachment;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Outbound port: attachment metadata ({@code messaging.attachments}). Bytes go to {@link AttachmentStorage}. */
public interface AttachmentStore {

    record StoredAttachment(
            String id,
            String merchantId,
            String storageKey,
            String fileName,
            String contentType,
            long byteSize,
            String uploadedBy,
            Instant createdAt) {
        public Attachment view() {
            return new Attachment(id, fileName, contentType, byteSize);
        }
    }

    void insert(StoredAttachment attachment);

    Optional<StoredAttachment> find(String merchantId, String id);

    /** The ids among {@code ids} that belong to {@code merchantId}. */
    List<String> existing(String merchantId, Collection<String> ids);
}
