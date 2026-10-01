package ca.northline.account.web;

import ca.northline.account.application.Cases.Detail;
import ca.northline.account.application.Cases.Row;
import ca.northline.account.application.Cases.ViewCases;
import ca.northline.account.application.Problems.Context;
import ca.northline.account.application.Problems.Report;
import ca.northline.account.application.Problems.ReportProblems;
import ca.northline.account.application.Problems.Reported;
import ca.northline.account.domain.ProblemRules;
import ca.northline.messaging.api.CustomerCaseDesk;
import ca.northline.messaging.api.CustomerCaseDesk.Attachment;
import ca.northline.shared.Bytes;
import ca.northline.shared.ListResponse;
import ca.northline.shared.NotFound;
import ca.northline.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * "Something's wrong" and Help &amp; cases (S-60). Reporting opens cases; it never refunds — the business reviews each
 * refund case for 24 h and Northline's agents decide the rest.
 *
 * <pre>
 * GET  /api/v1/me/problems/{kind}/{id}     kind order | food | booking: what can be reported, until when, the reasons
 * POST /api/v1/me/problems                 {kind, id, items, reason, note?, attachmentIds?, triageCategory?, triageSummary?}
 *                                          → 201 {caseId, caseCode, refunds: [RF-…], submittedAt, totalCents, card}
 * POST /api/v1/me/case-uploads             multipart "file" (JPG, PNG, HEIC or PDF ≤ 10 MB) → 201 {id, fileName, …}
 * GET  /api/v1/me/case-uploads/{id}        the caller's own upload
 * GET  /api/v1/me/cases                    the caller's refund cases and disputes, newest first
 * GET  /api/v1/me/cases/{caseId}           one case: timeline, the card refunds go to, the conversation
 * POST /api/v1/me/cases/{caseId}/notes     {body, attachmentIds?} — add a message or photos to the case
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
class MyProblemsController {

    private final ReportProblems problems;
    private final ViewCases cases;
    private final CustomerCaseDesk desk;

    record ReportRequest(
            @NotBlank(message = ProblemRules.ITEMS_REQUIRED) @Pattern(regexp = "order|food|booking", message = ProblemRules.ITEMS_REQUIRED)
                    String kind,
            @NotBlank(message = ProblemRules.ITEMS_REQUIRED) String id,
            @Nullable List<String> items,
            @NotBlank(message = ProblemRules.REASON_REQUIRED) String reason,
            @Nullable @Size(max = ProblemRules.NOTE_MAX, message = ProblemRules.NOTE_TOO_LONG) String note,
            @Nullable List<String> attachmentIds,
            @Nullable String triageCategory,
            @Nullable @Size(max = 300) String triageSummary) {}

    record NoteRequest(@Nullable String body, @Nullable List<String> attachmentIds) {}

    @Operation(summary = "What can be reported on an order, food order or booking")
    @GetMapping("/problems/{kind}/{id}")
    ResponseEntity<Context> context(CurrentUser user, @PathVariable String kind, @PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(problems.context(user.userId(), kind, id));
    }

    @Operation(summary = "Report a problem: opens refund cases and a case for Northline (never an instant refund)")
    @PostMapping("/problems")
    @ResponseStatus(HttpStatus.CREATED)
    Reported report(CurrentUser user, @Valid @RequestBody ReportRequest b, Locale locale) {
        return problems.report(
                user.userId(),
                new Report(
                        b.kind(),
                        b.id(),
                        Objects.requireNonNullElse(b.items(), List.of()),
                        b.reason(),
                        b.note(),
                        Objects.requireNonNullElse(b.attachmentIds(), List.of()),
                        b.triageCategory(),
                        b.triageSummary()),
                locale);
    }

    @Operation(summary = "Upload a photo for a report or a case")
    @PostMapping(path = "/case-uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Attachment upload(CurrentUser user, @RequestParam("file") MultipartFile file) throws IOException {
        return desk.upload(
                user.userId(),
                Objects.requireNonNullElse(file.getOriginalFilename(), ""),
                Objects.requireNonNullElse(file.getContentType(), "application/octet-stream"),
                Bytes.of(file.getBytes()));
    }

    @Operation(summary = "The caller's own upload")
    @GetMapping("/case-uploads/{uploadId}")
    ResponseEntity<byte[]> uploaded(CurrentUser user, @PathVariable String uploadId) {
        var c = desk.content(user.userId(), uploadId).orElseThrow(() -> new NotFound("upload", uploadId));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(c.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(c.fileName()).build().toString())
                .body(c.bytes().toArray());
    }

    @Operation(summary = "The caller's refund cases and disputes")
    @GetMapping("/cases")
    ResponseEntity<ListResponse<Row>> list(CurrentUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ListResponse<>(cases.cases(user.userId())));
    }

    @Operation(summary = "One case: its timeline and conversation")
    @GetMapping("/cases/{caseId}")
    ResponseEntity<Detail> detail(CurrentUser user, @PathVariable String caseId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cases.detail(user.userId(), caseId));
    }

    @Operation(summary = "Add a message or photos to a case")
    @PostMapping("/cases/{caseId}/notes")
    Detail note(CurrentUser user, @PathVariable String caseId, @RequestBody NoteRequest b) {
        return cases.addNote(
                user.userId(),
                caseId,
                Objects.requireNonNullElse(b.body(), ""),
                Objects.requireNonNullElse(b.attachmentIds(), List.of()));
    }
}
