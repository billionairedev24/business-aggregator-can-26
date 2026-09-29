package ca.northline.messaging.persistence;

import static ca.northline.messaging.persistence.MessagingSql.requiredInstant;
import static ca.northline.messaging.persistence.MessagingSql.ts;

import ca.northline.messaging.application.AttachmentStore;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Attachment metadata over {@code messaging.attachments}. */
@Repository
@RequiredArgsConstructor
class MessageAttachmentAdapter implements AttachmentStore {

    private final JdbcClient jdbc;

    @Override
    public void insert(StoredAttachment a) {
        jdbc.sql("""
                        insert into messaging.attachments (id, merchant_id, storage_key, file_name, content_type,
                                                           byte_size, uploaded_by, created_at)
                        values (:id, :m, :key, :name, :type, :size, :by, :at)
                        """)
                .param("id", a.id())
                .param("m", a.merchantId())
                .param("key", a.storageKey())
                .param("name", a.fileName())
                .param("type", a.contentType())
                .param("size", a.byteSize())
                .param("by", a.uploadedBy())
                .param("at", ts(a.createdAt()))
                .update();
    }

    @Override
    public Optional<StoredAttachment> find(String merchantId, String id) {
        return jdbc.sql("select * from messaging.attachments where merchant_id = :m and id = :id")
                .param("m", merchantId)
                .param("id", id)
                .query((rs, _) -> new StoredAttachment(
                        rs.getString("id"),
                        rs.getString("merchant_id"),
                        rs.getString("storage_key"),
                        rs.getString("file_name"),
                        rs.getString("content_type"),
                        rs.getLong("byte_size"),
                        rs.getString("uploaded_by"),
                        requiredInstant(rs, "created_at")))
                .optional();
    }

    @Override
    public List<String> existing(String merchantId, Collection<String> ids) {
        return jdbc.sql("select id from messaging.attachments where merchant_id = :m and id = any(:ids)")
                .param("m", merchantId)
                .param("ids", ids.toArray(String[]::new))
                .query((rs, _) -> rs.getString("id"))
                .list();
    }
}
