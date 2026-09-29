package ca.northline.messaging.web;

import static ca.northline.messaging.domain.MessagingRules.FILE_REQUIRED;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.messaging.application.BrowseInbox.Attachment;
import ca.northline.messaging.application.ManageMessageAttachments;
import ca.northline.shared.NotFound;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Files for messages and help cases: {@code POST /message-attachments} (multipart {@code file}), {@code GET …/{id}}. */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/message-attachments")
@RequiredArgsConstructor
class MessageAttachmentController {

    private final ManageMessageAttachments attachments;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresMerchant(VIEW)
    Attachment upload(
            @PathVariable String merchantId,
            @RequestParam(name = "file", required = false) @Nullable MultipartFile file,
            CurrentMember member) {
        if (file == null || file.isEmpty()) {
            throw RuleViolation.of("file", "required", FILE_REQUIRED);
        }
        try {
            return attachments.upload(new ManageMessageAttachments.Upload(
                    merchantId,
                    member.userId(),
                    Objects.requireNonNullElse(file.getOriginalFilename(), ""),
                    Objects.requireNonNullElse(file.getContentType(), "application/octet-stream"),
                    file.getBytes()));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @GetMapping("/{attachmentId}")
    @RequiresMerchant(VIEW)
    ResponseEntity<byte[]> content(@PathVariable String merchantId, @PathVariable String attachmentId) {
        var content = attachments
                .content(merchantId, attachmentId)
                .orElseThrow(() -> new NotFound("attachment", attachmentId));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(content.fileName(), java.nio.charset.StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(content.bytes());
    }
}
