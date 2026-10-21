package ca.northline.catalogue.web;

import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.catalogue.application.ViewListingReview;
import ca.northline.shared.security.RequiresMerchant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-156: the reviewer's latest decision on a listing (reasons and note), for the Studio's rejected-listing notice.
 *
 * <pre>
 * GET /api/v1/merchants/{merchantId}/listings/{listingId}/review   {decision, reasons, note, decidedAt} · 204 none (VIEW)
 * </pre>
 */
@RestController
@RequiredArgsConstructor
class ListingReviewController {

    private final ViewListingReview reviews;

    @GetMapping("/api/v1/merchants/{merchantId}/listings/{listingId}/review")
    @RequiresMerchant(VIEW)
    ResponseEntity<ViewListingReview.Review> review(@PathVariable String merchantId, @PathVariable String listingId) {
        return reviews.latest(merchantId, listingId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
