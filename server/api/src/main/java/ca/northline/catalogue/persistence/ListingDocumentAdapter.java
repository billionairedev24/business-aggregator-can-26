package ca.northline.catalogue.persistence;

import static ca.northline.catalogue.persistence.Sql.requiredInstant;
import static ca.northline.catalogue.persistence.Sql.ts;

import ca.northline.catalogue.application.ListingDocumentStore;
import ca.northline.catalogue.domain.DocumentPurpose;
import ca.northline.catalogue.domain.ListingDocument;
import ca.northline.shared.CodedEnum;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link ListingDocumentStore} over {@code catalogue.listing_documents} (S-65). */
@Repository
@RequiredArgsConstructor
class ListingDocumentAdapter implements ListingDocumentStore {

    private final JdbcClient jdbc;

    @Override
    public void insert(ListingDocument d) {
        jdbc.sql("""
                        insert into catalogue.listing_documents (id, merchant_id, offer_id, purpose, file_name, content_type,
                                                                 byte_size, storage_key, uploaded_by, created_at)
                        values (:id, :m, :offer, :purpose, :name, :type, :size, :key, :by, :at)
                        """)
                .param("id", d.id())
                .param("m", d.merchantId())
                .param("offer", d.listingId())
                .param("purpose", d.purpose().code())
                .param("name", d.fileName())
                .param("type", d.contentType())
                .param("size", d.byteSize())
                .param("key", d.storageKey())
                .param("by", d.uploadedBy())
                .param("at", ts(d.createdAt()))
                .update();
    }

    @Override
    public List<ListingDocument> of(String merchantId, String listingId) {
        return jdbc.sql("""
                        select * from catalogue.listing_documents where merchant_id = :m and offer_id = :offer
                         order by created_at, id
                        """)
                .param("m", merchantId)
                .param("offer", listingId)
                .query(this::row)
                .list();
    }

    @Override
    public Optional<ListingDocument> find(String merchantId, String listingId, String documentId) {
        return jdbc.sql("""
                        select * from catalogue.listing_documents where id = :id and merchant_id = :m and offer_id = :offer
                        """)
                .param("id", documentId)
                .param("m", merchantId)
                .param("offer", listingId)
                .query(this::row)
                .optional();
    }

    @Override
    public void delete(String documentId) {
        jdbc.sql("delete from catalogue.listing_documents where id = :id")
                .param("id", documentId)
                .update();
    }

    private ListingDocument row(ResultSet rs, int rowNum) throws SQLException {
        return new ListingDocument(
                rs.getString("id"),
                rs.getString("merchant_id"),
                rs.getString("offer_id"),
                CodedEnum.fromCode(DocumentPurpose.class, rs.getString("purpose")),
                rs.getString("file_name"),
                rs.getString("content_type"),
                rs.getInt("byte_size"),
                rs.getString("storage_key"),
                rs.getString("uploaded_by"),
                requiredInstant(rs, "created_at"));
    }
}
