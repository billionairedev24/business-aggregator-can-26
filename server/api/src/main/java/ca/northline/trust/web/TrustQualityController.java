package ca.northline.trust.web;

import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.shared.NotFound;
import ca.northline.shared.security.RequiresMerchant;
import ca.northline.trust.api.QualityQuery;
import ca.northline.trust.api.QualityQuery.QualityScore;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/merchants/{id}/quality} — the latest quality score (404 before the first nightly run). */
@RestController
@RequiredArgsConstructor
class TrustQualityController {

    private final QualityQuery quality;

    @GetMapping("/api/v1/merchants/{merchantId}/quality")
    @RequiresMerchant(VIEW)
    QualityScore latest(@PathVariable String merchantId) {
        return quality.latest(merchantId).orElseThrow(() -> new NotFound("quality score", merchantId));
    }
}
