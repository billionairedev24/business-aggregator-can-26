package ca.northline.booking.persistence;

import ca.northline.booking.application.MediaCatalog;
import ca.northline.shared.JdbcTimes;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class MediaCatalogJdbc implements MediaCatalog {

    private final JdbcClient jdbc;

    @Override
    public void insert(MediaInfo m) {
        jdbc.sql("""
                        insert into booking.media (id, merchant_id, file_name, content_type, size_bytes, storage_key, created_by,
                               created_at)
                        values (:id, :merchantId, :fileName, :contentType, :size, :key, :createdBy, :createdAt)
                        """)
                .param("id", m.id())
                .param("merchantId", m.merchantId())
                .param("fileName", m.fileName())
                .param("contentType", m.contentType())
                .param("size", m.sizeBytes())
                .param("key", m.storageKey())
                .param("createdBy", m.createdBy())
                .param("createdAt", JdbcTimes.ts(m.createdAt()))
                .update();
    }

    @Override
    public List<MediaInfo> find(String merchantId, Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select id, merchant_id, file_name, content_type, size_bytes, storage_key, created_by, created_at
                          from booking.media where merchant_id = :merchantId and id in (:ids)
                        """)
                .param("merchantId", merchantId)
                .param("ids", List.copyOf(ids))
                .query((rs, _) -> new MediaInfo(
                        rs.getString("id"),
                        rs.getString("merchant_id"),
                        rs.getString("file_name"),
                        rs.getString("content_type"),
                        rs.getLong("size_bytes"),
                        rs.getString("storage_key"),
                        rs.getString("created_by"),
                        JdbcTimes.requiredInstant(rs, "created_at")))
                .list();
    }
}
