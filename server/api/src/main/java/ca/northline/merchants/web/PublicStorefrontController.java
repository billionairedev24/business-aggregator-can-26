package ca.northline.merchants.web;

import ca.northline.merchants.application.CountStorefrontVisit;
import ca.northline.merchants.application.StorefrontUseCases.ResolveStorefrontHost;
import ca.northline.merchants.application.StorefrontUseCases.ViewPublishedStorefront;
import ca.northline.merchants.web.StorefrontDtos.PublicStorefrontResponse;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public read of published pages ({@code /api/v1/storefronts/**} and {@code /api/v1/public/**} are open in
 * SecurityConfig): consumer web and app. The by-host lookup is how the consumer app serves merchants' own domains (S-31,
 * contract in docs/runbooks/custom-domains.md § Routing).
 */
@RestController
@RequiredArgsConstructor
class PublicStorefrontController {

    private final ViewPublishedStorefront published;
    private final CountStorefrontVisit visits;
    private final ResolveStorefrontHost hosts;
    private final StorefrontWebMapper mapper;

    @GetMapping("/api/v1/storefronts/{slug}")
    PublicStorefrontResponse get(@PathVariable String slug) {
        return mapper.toPublic(published.bySlug(slug));
    }

    /**
     * S-75: the consumer page reports a visit (once per tab session). 204, nothing stored but the day's count; 404 for a
     * page that isn't published.
     */
    @PostMapping("/api/v1/public/storefronts/{slug}/visits")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void visit(
            @PathVariable String slug,
            @RequestHeader(value = "User-Agent", required = false) @Nullable String userAgent) {
        visits.count(slug, userAgent);
    }

    @GetMapping("/api/v1/storefronts/{slug}/logo")
    ResponseEntity<byte[]> logo(@PathVariable String slug) {
        return OnboardingDocumentController.file(published.logo(slug));
    }

    /**
     * The page a live custom domain serves; 404 for any other host (including Northline's own). Cacheable for a minute:
     * {@code custom_domain.changed} tells the consumer app when to drop an entry sooner.
     */
    @GetMapping("/api/v1/public/storefronts/by-host")
    ResponseEntity<PublicStorefrontResponse> byHost(@RequestParam String host) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(1)).cachePublic())
                .body(mapper.toPublic(hosts.byHost(host)));
    }
}
