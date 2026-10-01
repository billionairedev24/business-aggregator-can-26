package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.ListingDocument;
import java.util.List;
import java.util.Optional;

/** Outbound port (S-65): {@code catalogue.listing_documents} rows; the bytes go to {@link MediaStorage}. */
public interface ListingDocumentStore {

    void insert(ListingDocument document);

    /** The listing's documents, oldest first. */
    List<ListingDocument> of(String merchantId, String listingId);

    Optional<ListingDocument> find(String merchantId, String listingId, String documentId);

    void delete(String documentId);
}
