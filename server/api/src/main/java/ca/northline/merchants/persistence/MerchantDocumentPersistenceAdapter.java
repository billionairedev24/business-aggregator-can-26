package ca.northline.merchants.persistence;

import static ca.northline.merchants.persistence.ApplicationPersistenceAdapter.instant;
import static ca.northline.merchants.persistence.ApplicationPersistenceAdapter.timestamp;

import ca.northline.merchants.application.DocumentRepository;
import ca.northline.merchants.domain.Document;
import ca.northline.shared.CodedEnum;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class MerchantDocumentPersistenceAdapter implements DocumentRepository {

    private static final String COLUMNS = """
            select id, merchant_id, purpose, file_name, content_type, size_bytes, storage_key, uploaded_by, created_at
              from merchants.documents
            """;

    private final JdbcClient jdbc;

    @Override
    public void insert(Document d) {
        jdbc.sql("""
                        insert into merchants.documents (id, merchant_id, purpose, file_name, content_type, size_bytes,
                               storage_key, uploaded_by, created_at)
                        values (:id, :m, :purpose, :name, :type, :size, :key, :by, :at)
                        """)
                .param("id", d.id())
                .param("m", d.merchantId())
                .param("purpose", d.purpose().code())
                .param("name", d.fileName())
                .param("type", d.contentType())
                .param("size", d.sizeBytes())
                .param("key", d.storageKey())
                .param("by", d.uploadedBy())
                .param("at", timestamp(d.createdAt()))
                .update();
    }

    @Override
    public Optional<Document> find(String merchantId, String documentId) {
        return jdbc.sql(COLUMNS + " where merchant_id = :m and id = :id")
                .param("m", merchantId)
                .param("id", documentId)
                .query((rs, _) -> toDomain(rs))
                .optional();
    }

    @Override
    public List<Document> findAll(String merchantId, Collection<String> documentIds) {
        if (documentIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(COLUMNS + " where merchant_id = :m and id in (:ids)")
                .param("m", merchantId)
                .param("ids", List.copyOf(documentIds))
                .query((rs, _) -> toDomain(rs))
                .list();
    }

    private static Document toDomain(ResultSet rs) throws SQLException {
        return new Document(
                rs.getString("id"),
                rs.getString("merchant_id"),
                CodedEnum.fromCode(Document.Purpose.class, rs.getString("purpose")),
                rs.getString("file_name"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                rs.getString("storage_key"),
                rs.getString("uploaded_by"),
                Objects.requireNonNull(instant(rs, "created_at")));
    }
}
