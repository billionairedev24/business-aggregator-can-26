package ca.northline.standing.web;

import static ca.northline.shared.security.MerchantPermission.EDIT;
import static ca.northline.shared.security.MerchantPermission.MANAGE;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.shared.Bytes;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import ca.northline.standing.application.StandingUseCases.AppealView;
import ca.northline.standing.application.StandingUseCases.BusinessStanding;
import ca.northline.standing.application.StandingUseCases.CoachingView;
import ca.northline.standing.application.StandingUseCases.FileAppeal;
import ca.northline.standing.application.StandingUseCases.TickCoachingTask;
import ca.northline.standing.application.StandingUseCases.Upload;
import ca.northline.standing.application.StandingUseCases.ViewStanding;
import ca.northline.standing.domain.AppealRules;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Studio › Standing (S-156): the consequences in force with their reason and how to appeal, appeals and their outcome,
 * dispute decisions still open to appeal, the coaching plan. Reading: every team member; appealing: owners (it speaks
 * for the business); ticking coaching tasks: owners, technicians and cooks.
 *
 * <pre>
 * GET  /api/v1/merchants/{merchantId}/standing                                 BusinessStanding (VIEW)
 * POST /api/v1/merchants/{merchantId}/standing/appeals {subjectType, subjectId, statement}   201 Appeal (MANAGE)
 *      409 already_appealed · not_appealable · appeal_window_closed · consequence_lifted
 * POST /api/v1/merchants/{merchantId}/standing/appeals/{id}/files   raw file, Content-Type + X-File-Name   201 Appeal
 * GET  /api/v1/merchants/{merchantId}/standing/appeals/{id}/files/{fileId}     the file (VIEW)
 * PUT  /api/v1/merchants/{merchantId}/standing/coaching/{planId}/tasks/{taskId} {done}       Coaching (EDIT)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/standing")
@RequiredArgsConstructor
class StudioStandingController {

    private final ViewStanding view;
    private final FileAppeal appeals;
    private final TickCoachingTask coaching;

    record AppealRequest(
            @NotBlank(message = AppealRules.SUBJECT_REQUIRED)
            @Pattern(regexp = "dispute|consequence", message = AppealRules.SUBJECT_REQUIRED)
            String subjectType,

            @NotBlank(message = AppealRules.SUBJECT_REQUIRED)
            String subjectId,

            @NotBlank(message = AppealRules.STATEMENT_REQUIRED)
            @Size(max = AppealRules.STATEMENT_MAX, message = AppealRules.STATEMENT_TOO_LONG)
            String statement) {}

    record TickRequest(@NotNull(message = DONE_REQUIRED) Boolean done) {}

    static final String DONE_REQUIRED = "Choose done or not done.";

    @GetMapping
    @RequiresMerchant(VIEW)
    BusinessStanding standing(@PathVariable String merchantId) {
        return view.business(merchantId);
    }

    @PostMapping("/appeals")
    @RequiresMerchant(MANAGE)
    ResponseEntity<AppealView> appeal(
            @PathVariable String merchantId, @Valid @RequestBody AppealRequest body, CurrentMember member) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(appeals.file(
                        merchantId, body.subjectType(), body.subjectId(), body.statement(), member.userId()));
    }

    @PostMapping(path = "/appeals/{appealId}/files", consumes = MediaType.ALL_VALUE)
    @RequiresMerchant(MANAGE)
    ResponseEntity<AppealView> file(
            @PathVariable String merchantId,
            @PathVariable String appealId,
            @RequestHeader(name = "X-File-Name", required = false) @Nullable String fileName,
            HttpServletRequest request) {
        byte[] bytes;
        try (var in = request.getInputStream()) {
            bytes = in.readNBytes((int) AppealRules.FILE_MAX_BYTES + 1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (bytes.length > AppealRules.FILE_MAX_BYTES) {
            throw RuleViolation.of("file", "size", AppealRules.FILE_SIZE);
        }
        var name = fileName == null || fileName.isBlank()
                ? "evidence"
                : URLDecoder.decode(fileName, StandardCharsets.UTF_8).strip();
        var type = Objects.requireNonNullElse(request.getContentType(), "application/octet-stream");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(appeals.attach(merchantId, appealId, new Upload(name, type, Bytes.of(bytes))));
    }

    @GetMapping("/appeals/{appealId}/files/{fileId}")
    @RequiresMerchant(VIEW)
    ResponseEntity<byte[]> download(
            @PathVariable String merchantId, @PathVariable String appealId, @PathVariable String fileId) {
        var f = appeals.file(merchantId, appealId, fileId).orElseThrow(() -> new NotFound("file", fileId));
        return Files.response(f.bytes(), f.contentType(), f.name());
    }

    @PutMapping("/coaching/{planId}/tasks/{taskId}")
    @RequiresMerchant(EDIT)
    CoachingView tick(
            @PathVariable String merchantId,
            @PathVariable String planId,
            @PathVariable String taskId,
            @Valid @RequestBody TickRequest body,
            CurrentMember member) {
        return coaching.tick(merchantId, planId, taskId, body.done(), member.userId());
    }

    /** Stored files are served inline, never sniffed, never cached. */
    static final class Files {
        private Files() {}

        static ResponseEntity<byte[]> response(Bytes bytes, String contentType, String name) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .cacheControl(CacheControl.noStore())
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.inline()
                                    .filename(name, StandardCharsets.UTF_8)
                                    .build()
                                    .toString())
                    .header("X-Content-Type-Options", "nosniff")
                    .body(bytes.toArray());
        }
    }
}
