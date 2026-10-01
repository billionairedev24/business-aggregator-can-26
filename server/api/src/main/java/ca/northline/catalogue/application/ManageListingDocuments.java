package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.DocumentPurpose;
import ca.northline.catalogue.domain.ListingDocument;
import ca.northline.shared.Bytes;
import java.util.List;
import java.util.Optional;

/** S-65: the Compliance tab's documents — upload a spec sheet or an invoice, list, download, remove. */
public interface ManageListingDocuments {

    record Upload(
            String merchantId,
            String listingId,
            DocumentPurpose purpose,
            String fileName,
            Bytes bytes,
            String actorId) {}

    record Content(Bytes bytes, String contentType, String fileName) {}

    ListingDocument upload(Upload upload);

    List<ListingDocument> list(String merchantId, String listingId);

    Optional<Content> content(String merchantId, String listingId, String documentId);

    void delete(String merchantId, String listingId, String documentId);
}
