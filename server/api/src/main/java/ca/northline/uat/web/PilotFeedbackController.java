package ca.northline.uat.web;

import ca.northline.shared.Bytes;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.ListResponse;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.CurrentUser;
import ca.northline.uat.application.PilotFeedback;
import ca.northline.uat.application.PilotFeedback.Mine;
import ca.northline.uat.application.PilotFeedback.Sent;
import ca.northline.uat.application.PilotFeedback.Status;
import ca.northline.uat.application.PilotFeedback.Submission;
import ca.northline.uat.application.PilotFeedback.Uploaded;
import ca.northline.uat.domain.FeedbackApp;
import ca.northline.uat.domain.FeedbackCategory;
import ca.northline.uat.domain.FeedbackRules;
import ca.northline.uat.domain.Severity;
import ca.northline.uat.web.UatBodies.FeedbackBody;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * A pilot participant's UAT feedback (S-121), from the Studio, the consumer web, the console and the consumer app. Any
 * signed-in person may ask whether they take part; only participants may upload and send (403 otherwise).
 *
 * <pre>
 * GET  /api/v1/me/pilot[?merchantId=][&amp;app=courier]   {participant, persona, screenshotMaxBytes, screenshotTypes}
 *                                       (app=courier: only as a pilot courier — the courier app's button)
 * POST /api/v1/me/pilot/screenshots     multipart "file" (PNG or JPEG, ≤ 5 MB) → 201 {id, contentType, size}
 * POST /api/v1/me/pilot/feedback        FeedbackBody → 201 {id, reference}
 * GET  /api/v1/me/pilot/feedback        {items: [what I sent, with its triage state]}
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/me/pilot")
@RequiredArgsConstructor
class PilotFeedbackController {

    private final PilotFeedback feedback;

    @Operation(summary = "Whether the caller (or the business they act for) takes part in the pilot")
    @GetMapping
    Status status(
            CurrentUser user,
            @RequestParam(required = false) @Nullable String merchantId,
            @RequestParam(required = false) @Nullable String app) {
        return feedback.status(user.userId(), merchantId, "courier".equals(app) ? FeedbackApp.COURIER : null);
    }

    @Operation(summary = "Upload a screenshot to attach to pilot feedback")
    @PostMapping(path = "/screenshots", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Uploaded screenshot(CurrentUser user, @RequestParam(name = "file", required = false) @Nullable MultipartFile file)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw RuleViolation.of("file", "required", FeedbackRules.SCREENSHOT_REQUIRED);
        }
        return feedback.screenshot(
                user.userId(),
                Objects.requireNonNullElse(file.getContentType(), "application/octet-stream"),
                Bytes.of(file.getBytes()));
    }

    @Operation(summary = "Send pilot feedback")
    @PostMapping("/feedback")
    @ResponseStatus(HttpStatus.CREATED)
    Sent send(CurrentUser user, @Valid @RequestBody FeedbackBody body) {
        return feedback.send(new Submission(
                user.userId(),
                body.merchantId(),
                CodedEnum.fromCode(FeedbackApp.class, body.app()),
                CodedEnum.fromCode(FeedbackCategory.class, body.category()),
                CodedEnum.fromCode(Severity.class, body.severity()),
                body.body(),
                body.route(),
                body.appVersion(),
                body.locale(),
                body.platform(),
                body.screenshotId()));
    }

    @Operation(summary = "The pilot feedback the caller sent, with its triage state")
    @GetMapping("/feedback")
    ListResponse<Mine> mine(CurrentUser user) {
        return new ListResponse<>(feedback.mine(user.userId()));
    }
}
