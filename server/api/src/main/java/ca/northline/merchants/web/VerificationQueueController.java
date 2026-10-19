package ca.northline.merchants.web;

import ca.northline.merchants.application.VerificationQueue;
import ca.northline.merchants.application.VerificationQueue.ApplicationDetail;
import ca.northline.merchants.application.VerificationQueue.DecideApplication;
import ca.northline.merchants.application.VerificationQueue.DecideIdentityReview;
import ca.northline.merchants.application.VerificationQueue.Decision;
import ca.northline.merchants.application.VerificationQueue.ListApplications;
import ca.northline.merchants.application.VerificationQueue.Queue;
import ca.northline.merchants.application.VerificationQueue.Recommendation;
import ca.northline.merchants.application.VerificationQueue.ReviewApplication;
import ca.northline.merchants.application.VerificationQueue.ViewApplication;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.PlaceFilter;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform console — the verification queue (S-79, design 03 {@code verify}; admin and trust &amp; safety open it,
 * reviewing needs {@code verify}, deciding {@code approve}). Category names follow {@code Accept-Language}.
 *
 * <p>Two-person review (four-eyes, 2026-10-18): stage 1 is {@code POST …/review} (a recommendation), stage 2 {@code
 * POST …/decision} by someone who didn't review the open round — 409 {@code review_required} before a review, 403
 * {@code same_person} for its reviewer (unless {@code northline.onboarding.four-eyes.enabled=false}).
 *
 * <pre>
 * GET  /api/v1/console/verification/applications[?province=AB][&amp;market=calgary]     Queue {items, pending, medianDecisionHours}
 * GET  /api/v1/console/verification/applications/{businessId}                       ApplicationDetail
 * POST /api/v1/console/verification/applications/{businessId}/review
 *      {recommendation: approve|reject|request_info, checkKeys?: [key], note?}      ApplicationDetail
 * POST /api/v1/console/verification/applications/{businessId}/decision
 *      {decision: approve|request_info, checkKeys?: [key], note?}                   ApplicationDetail
 * POST /api/v1/console/verification/applications/{businessId}/identity-reviews/{checkId}/decision
 *      {decision: approve|reject, note?}                                            ApplicationDetail
 * </pre>
 *
 * The path variable is {@code businessId}, not {@code merchantId}: these are staff endpoints, not a member's.
 */
@RestController
@RequestMapping("/api/v1/console/verification/applications")
@RequiredArgsConstructor
class VerificationQueueController {

    private final PlaceFilter places;
    private final ListApplications list;
    private final ViewApplication view;
    private final ReviewApplication review;
    private final DecideApplication decide;
    private final DecideIdentityReview decideIdentity;

    record DecisionRequest(
            @NotBlank(message = VerificationQueue.DECISION_REQUIRED)
            @Pattern(regexp = "approve|request_info", message = VerificationQueue.DECISION_REQUIRED)
            String decision,

            @Nullable List<String> checkKeys,

            @Nullable @Size(max = VerificationQueue.NOTE_MAX, message = VerificationQueue.TOO_LONG)
            String note) {}

    record ReviewRequest(
            @NotBlank(message = VerificationQueue.RECOMMENDATION_REQUIRED)
            @Pattern(regexp = "approve|reject|request_info", message = VerificationQueue.RECOMMENDATION_REQUIRED)
            String recommendation,

            @Nullable List<String> checkKeys,

            @Nullable @Size(max = VerificationQueue.NOTE_MAX, message = VerificationQueue.TOO_LONG)
            String note) {}

    record ReviewDecisionRequest(
            @NotBlank(message = VerificationQueue.REVIEW_DECISION_REQUIRED)
            @Pattern(regexp = "approve|reject", message = VerificationQueue.REVIEW_DECISION_REQUIRED)
            String decision,

            @Nullable @Size(max = VerificationQueue.NOTE_MAX, message = VerificationQueue.TOO_LONG)
            String note) {}

    @GetMapping
    @RequiresConsole(ConsoleScreen.VERIFY)
    Queue queue(
            @RequestParam(required = false) @Nullable String province,
            @RequestParam(required = false) @Nullable String market,
            Locale locale) {
        return list.list(places.resolve(province, market).scope(), lang(locale));
    }

    @GetMapping("/{businessId}")
    @RequiresConsole(ConsoleScreen.VERIFY)
    ApplicationDetail application(@PathVariable String businessId, Locale locale) {
        return view.view(businessId, lang(locale));
    }

    @PostMapping("/{businessId}/review")
    @RequiresConsole(value = ConsoleScreen.VERIFY, actions = ConsoleAction.VERIFY)
    ApplicationDetail review(
            @PathVariable String businessId,
            @Valid @RequestBody ReviewRequest body,
            CurrentStaff staff,
            Locale locale) {
        return review.review(
                new ReviewApplication.Command(
                        businessId,
                        CodedEnum.fromCode(Recommendation.class, body.recommendation()),
                        body.checkKeys() == null ? List.of() : body.checkKeys(),
                        body.note(),
                        staff.userId(),
                        staff.roleCodes()),
                lang(locale));
    }

    @PostMapping("/{businessId}/decision")
    @RequiresConsole(value = ConsoleScreen.VERIFY, actions = ConsoleAction.APPROVE)
    ApplicationDetail decide(
            @PathVariable String businessId,
            @Valid @RequestBody DecisionRequest body,
            CurrentStaff staff,
            Locale locale) {
        return decide.decide(
                new DecideApplication.Command(
                        businessId,
                        "approve".equals(body.decision()) ? Decision.APPROVED : Decision.INFO_REQUESTED,
                        body.checkKeys() == null ? List.of() : body.checkKeys(),
                        body.note(),
                        staff.userId(),
                        staff.roleCodes()),
                lang(locale));
    }

    @PostMapping("/{businessId}/identity-reviews/{checkId}/decision")
    @RequiresConsole(value = ConsoleScreen.VERIFY, actions = ConsoleAction.VERIFY)
    ApplicationDetail decideIdentity(
            @PathVariable String businessId,
            @PathVariable String checkId,
            @Valid @RequestBody ReviewDecisionRequest body,
            CurrentStaff staff,
            Locale locale) {
        return decideIdentity.decide(
                new DecideIdentityReview.Command(
                        businessId,
                        checkId,
                        "approve".equals(body.decision()),
                        body.note(),
                        staff.userId(),
                        staff.roleCodes()),
                lang(locale));
    }

    static String lang(Locale locale) {
        return "fr".equals(locale.getLanguage()) ? "fr" : "en";
    }
}
