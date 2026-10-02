package ca.northline.privacy.web;

import ca.northline.privacy.application.PrivacyRequests.Downloads;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The access export behind a short-lived link (S-105): no session — the token is the authorization, so the app's
 * share sheet or a browser tab can fetch it. {@code part=data} (default) is the JSON, {@code part=summary} the
 * readable summary. An unknown or expired link is a 404.
 *
 * <pre>
 * GET /api/v1/public/privacy-exports/{token}[?part=data|summary]
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/public/privacy-exports")
@RequiredArgsConstructor
class PrivacyExportController {

    private final Downloads downloads;

    @Operation(summary = "Download a privacy export (JSON data or readable summary) with its link token")
    @GetMapping("/{token}")
    ResponseEntity<byte[]> download(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token,
            @RequestParam(defaultValue = "data") @Pattern(regexp = "data|summary") String part) {
        var file = downloads.download(token, part);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.fileName())
                                .build()
                                .toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.bytes().toArray());
    }
}
