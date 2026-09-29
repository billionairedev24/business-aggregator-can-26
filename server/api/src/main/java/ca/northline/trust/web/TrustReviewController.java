package ca.northline.trust.web;

import static ca.northline.shared.security.MerchantPermission.EDIT;
import static ca.northline.shared.security.MerchantPermission.VIEW;
import static ca.northline.trust.domain.ReviewRules.NOTE_MAX;
import static ca.northline.trust.domain.ReviewRules.NOTE_TOO_LONG;
import static ca.northline.trust.domain.ReviewRules.REASON_CODES;
import static ca.northline.trust.domain.ReviewRules.REASON_REQUIRED;
import static ca.northline.trust.domain.ReviewRules.REPLY_MAX;
import static ca.northline.trust.domain.ReviewRules.REPLY_REQUIRED;
import static ca.northline.trust.domain.ReviewRules.REPLY_TOO_LONG;

import ca.northline.shared.CodedEnum;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import ca.northline.trust.application.BrowseReviews;
import ca.northline.trust.application.BrowseReviews.Page;
import ca.northline.trust.application.BrowseReviews.Summary;
import ca.northline.trust.application.RespondToReview;
import ca.northline.trust.domain.ReportReason;
import ca.northline.trust.domain.Review;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
 * Studio › Reviews: {@code GET /reviews/summary}, {@code GET /reviews?limit=&offset=}, and the only two writes a
 * business has — {@code POST /reviews/{id}/reply} and {@code POST /reviews/{id}/report}. Owners, technicians and cooks
 * respond; bookkeepers read.
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/reviews")
@RequiredArgsConstructor
class TrustReviewController {

    record ReplyRequest(
            @NotBlank(message = REPLY_REQUIRED) @Size(max = REPLY_MAX, message = REPLY_TOO_LONG)
            String text) {}

    record ReportRequest(
            @NotBlank(message = REASON_REQUIRED) @Pattern(regexp = REASON_CODES, message = REASON_REQUIRED)
            String reason,

            @Nullable @Size(max = NOTE_MAX, message = NOTE_TOO_LONG)
            String note) {}

    private final BrowseReviews browse;
    private final RespondToReview respond;

    @GetMapping("/summary")
    @RequiresMerchant(VIEW)
    Summary summary(@PathVariable String merchantId) {
        return browse.summary(merchantId);
    }

    @GetMapping
    @RequiresMerchant(VIEW)
    Page list(
            @PathVariable String merchantId,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return browse.reviews(merchantId, Math.clamp(limit, 1, 50), Math.max(offset, 0));
    }

    @PostMapping("/{reviewId}/reply")
    @RequiresMerchant(EDIT)
    Review reply(
            @PathVariable String merchantId,
            @PathVariable String reviewId,
            @Valid @RequestBody ReplyRequest body,
            CurrentMember member) {
        return respond.reply(merchantId, reviewId, body.text(), member.userId());
    }

    @PostMapping("/{reviewId}/report")
    @RequiresMerchant(EDIT)
    Review report(
            @PathVariable String merchantId,
            @PathVariable String reviewId,
            @Valid @RequestBody ReportRequest body,
            CurrentMember member) {
        return respond.report(
                merchantId,
                reviewId,
                CodedEnum.fromCode(ReportReason.class, body.reason()),
                body.note(),
                member.userId());
    }
}
