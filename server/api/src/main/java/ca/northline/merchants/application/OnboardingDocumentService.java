package ca.northline.merchants.application;

import ca.northline.merchants.application.Documents.ReadDocument;
import ca.northline.merchants.application.Documents.UploadDocument;
import ca.northline.merchants.domain.Document;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores uploads through {@link DocumentStorage} and records them in {@code merchants.documents}. */
@Service
@RequiredArgsConstructor
@Transactional
class OnboardingDocumentService implements UploadDocument, ReadDocument {

    private static final Set<String> EVIDENCE_TYPES = Set.of("application/pdf", "image/png", "image/jpeg");
    private static final Set<String> LOGO_TYPES = Set.of("image/svg+xml", "image/png");

    private final DocumentRepository documents;
    private final DocumentStorage storage;
    private final Clock clock;

    @Override
    public Document upload(UploadDocument.Command command) {
        if (command.bytes().length == 0) {
            throw RuleViolation.of(Documents.FIELD, "required", Documents.EMPTY);
        }
        var logo = command.purpose() == Document.Purpose.LOGO;
        var type = command.contentType().toLowerCase(Locale.ROOT).split(";")[0].strip();
        if (command.bytes().length > Documents.MAX_BYTES || !(logo ? LOGO_TYPES : EVIDENCE_TYPES).contains(type)) {
            throw RuleViolation.of(Documents.FIELD, "type", logo ? Documents.LOGO_TYPE : Documents.TYPE_OR_SIZE);
        }
        var id = Ids.next();
        var key = storage.put(command.merchantId(), id, type, command.bytes());
        var name =
                command.fileName().isBlank() ? "document" : command.fileName().strip();
        var document = new Document(
                id,
                command.merchantId(),
                command.purpose(),
                name.length() > 200 ? name.substring(name.length() - 200) : name,
                type,
                command.bytes().length,
                key,
                command.actorId(),
                clock.instant());
        documents.insert(document);
        return document;
    }

    @Override
    @Transactional(readOnly = true)
    public Content read(String merchantId, String documentId) {
        var document = documents.find(merchantId, documentId).orElseThrow(() -> new NotFound("document", documentId));
        return new Content(document, storage.get(document.storageKey()));
    }
}
