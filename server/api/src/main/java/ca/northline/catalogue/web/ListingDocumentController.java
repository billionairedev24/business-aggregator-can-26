package ca.northline.catalogue.web;

import static ca.northline.shared.security.MerchantPermission.EDIT;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.catalogue.application.ManageListingDocuments;
import ca.northline.catalogue.domain.DocumentPurpose;
import ca.northline.catalogue.domain.ListingDocument;
import ca.northline.catalogue.domain.ListingMessages;
import ca.northline.shared.Bytes;
import ca.northline.shared.ListResponse;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * S-65: the product editor's Compliance documents.
 *
 * <pre>
 * GET    …/listings/{listingId}/documents                 the listing's documents, oldest first (VIEW)
 * POST   …/listings/{listingId}/documents                 multipart file + purpose spec_sheet|invoice → 201 (EDIT)
 * GET    …/listings/{listingId}/documents/{documentId}    the file, as an attachment, never cached (VIEW)
 * DELETE …/listings/{listingId}/documents/{documentId}    204 (EDIT)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/listings/{listingId}/documents")
@RequiredArgsConstructor
class ListingDocumentController {

    private final ManageListingDocuments documents;

    record DocumentResponse(
            String id,
            DocumentPurpose purpose,
            String fileName,
            String contentType,
            int byteSize,
            String url,
            Instant createdAt) {}

    @GetMapping
    @RequiresMerchant(VIEW)
    ListResponse<DocumentResponse> list(@PathVariable String merchantId, @PathVariable String listingId) {
        return new ListResponse<>(documents.list(merchantId, listingId).stream()
                .map(ListingDocumentController::response)
                .toList());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresMerchant(EDIT)
    DocumentResponse upload(
            @PathVariable String merchantId,
            @PathVariable String listingId,
            @RequestPart("file") @Nullable MultipartFile file,
            @RequestParam(required = false) @Nullable String purpose,
            CurrentMember member) {
        var chosen = purpose == null
                ? null
                : Arrays.stream(DocumentPurpose.values())
                        .filter(p -> p.code().equals(purpose))
                        .findFirst()
                        .orElse(null);
        if (chosen == null) {
            throw RuleViolation.of("purpose", "required", ListingMessages.DOCUMENT_PURPOSE);
        }
        if (file == null || file.isEmpty()) {
            throw RuleViolation.of("file", "required", ListingMessages.DOCUMENT_REQUIRED);
        }
        try {
            return response(documents.upload(new ManageListingDocuments.Upload(
                    merchantId,
                    listingId,
                    chosen,
                    file.getOriginalFilename() == null ? "" : file.getOriginalFilename(),
                    Bytes.of(file.getBytes()),
                    member.userId())));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @GetMapping("/{documentId}")
    @RequiresMerchant(VIEW)
    ResponseEntity<byte[]> content(
            @PathVariable String merchantId, @PathVariable String listingId, @PathVariable String documentId) {
        var content = documents
                .content(merchantId, listingId, documentId)
                .orElseThrow(() -> new NotFound("document", documentId));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(
                        "Content-Disposition",
                        ContentDisposition.attachment()
                                .filename(content.fileName(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(content.bytes().toArray());
    }

    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresMerchant(EDIT)
    void delete(@PathVariable String merchantId, @PathVariable String listingId, @PathVariable String documentId) {
        documents.delete(merchantId, listingId, documentId);
    }

    private static DocumentResponse response(ListingDocument d) {
        return new DocumentResponse(
                d.id(),
                d.purpose(),
                d.fileName(),
                d.contentType(),
                d.byteSize(),
                "/api/v1/merchants/%s/listings/%s/documents/%s".formatted(d.merchantId(), d.listingId(), d.id()),
                d.createdAt());
    }
}
