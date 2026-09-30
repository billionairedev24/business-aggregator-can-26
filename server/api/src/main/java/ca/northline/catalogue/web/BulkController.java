package ca.northline.catalogue.web;

import static ca.northline.shared.security.MerchantPermission.EDIT;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.catalogue.application.BulkImport;
import ca.northline.catalogue.domain.ImportBatch;
import ca.northline.catalogue.domain.ImportTemplate;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.ListResponse;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Bulk upload (validate → import, history) under {@code /listings}; commerce integrations: {@link CommerceController}. */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/listings")
@RequiredArgsConstructor
class BulkController {

    static final String FILE_REQUIRED = "Choose an .xlsx or .csv file.";
    static final String TEMPLATE_REQUIRED = "Choose a template.";

    private final BulkImport bulkImport;

    record ImportErrorResponse(int row, @Nullable String sku, String error) {}

    /** One upload: validation summary, the error report, and (after import) when it was imported. */
    record ImportResponse(
            String id,
            String fileName,
            ImportTemplate template,
            int rowCount,
            int createCount,
            int updateCount,
            int errorCount,
            List<ImportErrorResponse> errors,
            ImportBatch.Status status,
            Instant createdAt,
            @Nullable Instant importedAt) {

        static ImportResponse of(ImportBatch b) {
            return new ImportResponse(
                    b.id(),
                    b.fileName(),
                    b.template(),
                    b.rowCount(),
                    b.createCount(),
                    b.updateCount(),
                    b.errors().size(),
                    b.errors().stream()
                            .map(e -> new ImportErrorResponse(e.row(), e.sku(), e.error()))
                            .toList(),
                    b.status(),
                    b.createdAt(),
                    b.importedAt());
        }
    }

    @GetMapping("/imports")
    @RequiresMerchant(VIEW)
    ListResponse<ImportResponse> history(@PathVariable String merchantId) {
        return new ListResponse<>(
                bulkImport.history(merchantId).stream().map(ImportResponse::of).toList());
    }

    @PostMapping(path = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresMerchant(EDIT)
    ImportResponse upload(
            @PathVariable String merchantId,
            @RequestParam(required = false) @Nullable String template,
            @RequestPart("file") @Nullable MultipartFile file,
            CurrentMember member) {
        if (template == null || template.isBlank()) {
            throw RuleViolation.of("template", "required", TEMPLATE_REQUIRED);
        }
        ImportTemplate chosen;
        try {
            chosen = CodedEnum.fromCode(ImportTemplate.class, template);
        } catch (IllegalArgumentException ex) {
            throw RuleViolation.of("template", "required", TEMPLATE_REQUIRED);
        }
        if (file == null || file.isEmpty()) {
            throw RuleViolation.of("file", "required", FILE_REQUIRED);
        }
        try {
            var name = Objects.requireNonNullElse(file.getOriginalFilename(), "upload.csv");
            return ImportResponse.of(bulkImport.validate(merchantId, chosen, name, file.getBytes(), member.userId()));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @PostMapping("/imports/{importId}/commit")
    @RequiresMerchant(EDIT)
    ImportResponse commit(@PathVariable String merchantId, @PathVariable String importId, CurrentMember member) {
        return ImportResponse.of(bulkImport.commit(merchantId, importId, member.userId()));
    }
}
