package ca.northline.hire.web;

import ca.northline.hire.application.ProviderPages.ListProviderReviews;
import ca.northline.hire.application.ProviderPages.ProviderPage;
import ca.northline.hire.application.ProviderPages.ViewProvider;
import ca.northline.trust.api.PublicReviews.ReviewPage;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public provider page's facts (S-54). The page itself — sections in order, brand, tagline — is the storefront API
 * ({@code GET /api/v1/storefronts/{slug}}, or {@code /api/v1/public/storefronts/by-host} on a merchant's own domain);
 * this adds trust figures, services, service area, next slot and reviews. 404 unless the business is active, offers
 * services and has published its page.
 */
@RestController
@RequiredArgsConstructor
class PublicProvidersController {

    private final ViewProvider providers;
    private final ListProviderReviews reviews;

    @GetMapping("/api/v1/public/providers/{slug}")
    ResponseEntity<ProviderPage> provider(@PathVariable String slug, @RequestParam(defaultValue = "en") String lang) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(1)).cachePublic())
                .body(providers.provider(slug, Languages.of(lang)));
    }

    /** "Show more reviews": newest first, 10 per page by default (at most 20). */
    @GetMapping("/api/v1/public/providers/{slug}/reviews")
    ResponseEntity<ReviewPage> reviews(
            @PathVariable String slug,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(1)).cachePublic())
                .body(reviews.reviews(slug, limit, offset));
    }
}
