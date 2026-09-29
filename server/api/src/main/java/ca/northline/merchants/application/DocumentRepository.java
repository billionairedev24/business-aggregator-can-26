package ca.northline.merchants.application;

import ca.northline.merchants.domain.Document;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Outbound port: {@code merchants.documents} metadata. The bytes go through {@link DocumentStorage}. */
public interface DocumentRepository {
    void insert(Document document);

    Optional<Document> find(String merchantId, String documentId);

    List<Document> findAll(String merchantId, Collection<String> documentIds);
}
