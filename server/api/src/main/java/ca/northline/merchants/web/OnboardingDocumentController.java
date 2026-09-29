package ca.northline.merchants.web;

import static ca.northline.shared.security.MerchantPermission.MANAGE;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.merchants.application.Documents;
import ca.northline.merchants.application.Documents.ReadDocument;
import ca.northline.merchants.application.Documents.UploadDocument;
import ca.northline.merchants.domain.Document;
import ca.northline.merchants.web.OnboardingResponses.DocumentResponse;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Documents: {@code POST /api/v1/merchants/{merchantId}/onboarding/documents} (multipart {@code file} + {@code purpose} =
 * legal | verification | logo) and {@code GET …/onboarding/documents/{documentId}} (the bytes).
 */
@RestController
@RequiredArgsConstructor
class OnboardingDocumentController {

    private final UploadDocument uploadDocument;
    private final ReadDocument readDocument;
    private final OnboardingWebMapper mapper;

    @PostMapping(
            path = "/api/v1/merchants/{merchantId}/onboarding/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresMerchant(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    DocumentResponse upload(
            @PathVariable String merchantId,
            @RequestParam(name = "file", required = false) @Nullable MultipartFile file,
            @RequestParam(name = "purpose", defaultValue = "verification") String purpose,
            CurrentMember member) {
        if (file == null || file.isEmpty()) {
            throw RuleViolation.of(Documents.FIELD, "required", Documents.EMPTY);
        }
        Document.Purpose kind;
        try {
            kind = CodedEnum.fromCode(Document.Purpose.class, purpose);
        } catch (IllegalArgumentException _) {
            throw RuleViolation.of("purpose", "enum", "Pick legal, verification or logo.");
        }
        try {
            return mapper.toDocument(uploadDocument.upload(new UploadDocument.Command(
                    merchantId,
                    member.userId(),
                    kind,
                    Objects.requireNonNullElse(file.getOriginalFilename(), "document"),
                    Objects.requireNonNullElse(file.getContentType(), "application/octet-stream"),
                    file.getBytes())));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @GetMapping("/api/v1/merchants/{merchantId}/onboarding/documents/{documentId}")
    @RequiresMerchant(VIEW)
    ResponseEntity<byte[]> read(@PathVariable String merchantId, @PathVariable String documentId) {
        var content = readDocument.read(merchantId, documentId);
        return file(content);
    }

    static ResponseEntity<byte[]> file(ReadDocument.Content content) {
        var doc = content.document();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(doc.contentType()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(doc.fileName(), java.nio.charset.StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox")
                .body(content.bytes());
    }
}
