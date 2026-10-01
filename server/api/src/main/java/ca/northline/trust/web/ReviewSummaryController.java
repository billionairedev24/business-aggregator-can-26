package ca.northline.trust.web;

import static ca.northline.shared.security.MerchantPermission.EDIT;

import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import ca.northline.trust.application.DraftReviewSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-131: {@code POST /api/v1/merchants/{merchantId}/reviews/summary-draft} — an English and French summary of the
 * verified reviews, for the roles that may edit the business's page content ({@code EDIT}). A draft; never published.
 */
@RestController
@RequiredArgsConstructor
class ReviewSummaryController {

    private final DraftReviewSummary summaries;

    @PostMapping("/api/v1/merchants/{merchantId}/reviews/summary-draft")
    @RequiresMerchant(EDIT)
    DraftReviewSummary.Summary draft(@PathVariable String merchantId, CurrentMember member) {
        return summaries.summarize(merchantId, member.userId());
    }
}
