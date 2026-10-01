package ca.northline.catalogue.application;

import static ca.northline.catalogue.domain.ListingMessages.DOCUMENTS_MAX;
import static ca.northline.catalogue.domain.ListingMessages.DOCUMENTS_TOO_MANY;
import static ca.northline.catalogue.domain.ListingMessages.DOCUMENT_MAX_BYTES;
import static ca.northline.catalogue.domain.ListingMessages.DOCUMENT_REQUIRED;
import static ca.northline.catalogue.domain.ListingMessages.DOCUMENT_TYPE;

import ca.northline.catalogue.domain.ListingDocument;
import ca.northline.shared.Bytes;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.storage.ObjectKeys;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-65: compliance documents of a product listing. PDF, PNG or JPEG, judged by the file's first bytes (not its name or
 * the browser's type), at most 10 MB and 10 per listing — the same rule and message as onboarding documents. Stored
 * through {@link MediaStorage} under the business's prefix; served only to the business (and staff, by the console).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ListingDocumentService implements ManageListingDocuments {

    static final int FILE_NAME_MAX = 200;

    private final ListingRepository listings;
    private final ListingDocumentStore documents;
    private final MediaStorage storage;
    private final Clock clock;

    @Override
    @Transactional
    public ListingDocument upload(Upload upload) {
        requireProduct(upload.merchantId(), upload.listingId());
        if (upload.bytes().isEmpty()) {
            throw RuleViolation.of("file", "required", DOCUMENT_REQUIRED);
        }
        var bytes = upload.bytes().toArray();
        var type = sniff(bytes);
        if (type == null || bytes.length > DOCUMENT_MAX_BYTES) {
            throw RuleViolation.of("file", "format", DOCUMENT_TYPE);
        }
        if (documents.of(upload.merchantId(), upload.listingId()).size() >= DOCUMENTS_MAX) {
            throw RuleViolation.of("file", "length", DOCUMENTS_TOO_MANY);
        }
        var id = Ids.next();
        var document = new ListingDocument(
                id,
                upload.merchantId(),
                upload.listingId(),
                upload.purpose(),
                fileName(upload.fileName(), type),
                type,
                bytes.length,
                ObjectKeys.merchantObject(upload.merchantId(), id, type),
                upload.actorId(),
                clock.instant());
        storage.put(document.storageKey(), bytes, type);
        documents.insert(document);
        return document;
    }

    @Override
    public List<ListingDocument> list(String merchantId, String listingId) {
        requireProduct(merchantId, listingId);
        return documents.of(merchantId, listingId);
    }

    @Override
    public Optional<Content> content(String merchantId, String listingId, String documentId) {
        return documents
                .find(merchantId, listingId, documentId)
                .flatMap(d ->
                        storage.get(d.storageKey()).map(b -> new Content(Bytes.of(b), d.contentType(), d.fileName())));
    }

    @Override
    @Transactional
    public void delete(String merchantId, String listingId, String documentId) {
        var document = documents
                .find(merchantId, listingId, documentId)
                .orElseThrow(() -> new NotFound("document", documentId));
        documents.delete(document.id());
        storage.delete(document.storageKey());
    }

    private void requireProduct(String merchantId, String listingId) {
        if (listings.product(merchantId, listingId).isEmpty()) {
            throw new NotFound("listing", listingId);
        }
    }

    /** {@code application/pdf}, {@code image/png} or {@code image/jpeg} from the magic bytes; null otherwise. */
    static @Nullable String sniff(byte[] b) {
        if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') {
            return "application/pdf";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        return null;
    }

    /** The name the person sees: the last path segment, without control characters, at most 200 characters. */
    static String fileName(@Nullable String original, String type) {
        var name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1)
                .replaceAll("\\p{Cntrl}", "")
                .strip();
        if (name.isEmpty()) {
            name = "document." + (type.equals("application/pdf") ? "pdf" : type.substring("image/".length()));
        }
        return name.length() > FILE_NAME_MAX ? name.substring(name.length() - FILE_NAME_MAX) : name;
    }
}
