package ca.northline.studio.web;

import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.shared.security.RequiresMerchant;
import ca.northline.studio.application.StorefrontStats;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** S-75: {@code GET /api/v1/merchants/{merchantId}/storefront-stats} — visits and booked rate, last 30 days. */
@RestController
@RequiredArgsConstructor
class StorefrontStatsController {

    private final StorefrontStats stats;

    @GetMapping("/api/v1/merchants/{merchantId}/storefront-stats")
    @RequiresMerchant(VIEW)
    StorefrontStats.Stats stats(@PathVariable String merchantId) {
        return stats.of(merchantId);
    }
}
