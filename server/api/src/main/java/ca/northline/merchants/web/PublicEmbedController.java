package ca.northline.merchants.web;

import ca.northline.developer.api.EmbedKeys;
import ca.northline.merchants.application.StorefrontUseCases.ViewPublishedStorefront;
import ca.northline.merchants.domain.CtaLabel;
import ca.northline.merchants.domain.PageKind;
import ca.northline.shared.NotFound;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-76: what the website embed ({@code <site>/embed.js} on a business's own site) shows. Called cross-origin by the
 * script with the snippet's publishable key and page slug; the key must be active and belong to that page's business,
 * and the page must be published. A key limited to some sites answers only pages on those sites (the browser's {@code
 * Origin}). The answer is public data from the published page, readable from any site ({@code
 * Access-Control-Allow-Origin}); no cookies are involved.
 *
 * <pre>
 * GET /api/v1/public/embed?key=pk_live_…&amp;store={slug}
 * </pre>
 */
@RestController
@RequiredArgsConstructor
class PublicEmbedController {

    static final String SITE_NOT_ALLOWED = "This embed key isn't set up for this website.";

    private final EmbedKeys keys;
    private final ViewPublishedStorefront published;

    /**
     * @param path where the page is on the consumer site: business pages {@code /providers/<slug>}, menu pages
     *     {@code /food/<slug>}; store pages have no page of their own yet (S-49), so {@code /}
     */
    record EmbedResponse(
            String slug, PageKind pageKind, String name, String brandColor, CtaLabel ctaLabel, String path) {}

    @GetMapping("/api/v1/public/embed")
    ResponseEntity<?> embed(
            @RequestParam String key,
            @RequestParam String store,
            @RequestHeader(value = HttpHeaders.ORIGIN, required = false) @Nullable String origin) {
        var embedKey = keys.active(key).orElseThrow(() -> new NotFound("embed key", store));
        var view = published.bySlug(store); // 404 unless published and active
        var page = view.storefront();
        if (!page.getMerchantId().equals(embedKey.merchantId())) {
            throw new NotFound("embed key", store);
        }
        if (!embedKey.allowedOrigins().isEmpty() && (origin == null || !embedKey.allows(origin))) {
            var problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, SITE_NOT_ALLOWED);
            problem.setProperty("code", "site_not_allowed");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
        }
        var slug = page.getSlug().value();
        var path = switch (page.getPageKind()) {
            case BUSINESS_PAGE -> "/providers/" + slug;
            case MENU_PAGE -> "/food/" + slug;
            case STORE -> "/";
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, embedKey.allowedOrigins().isEmpty() || origin == null ? "*" : origin)
                .header(HttpHeaders.VARY, HttpHeaders.ORIGIN)
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(1)).cachePublic())
                .body(new EmbedResponse(
                        slug,
                        page.getPageKind(),
                        view.merchant().getDisplayName(),
                        page.getBrandColor().hex(),
                        page.getCtaLabel(),
                        path));
    }
}
