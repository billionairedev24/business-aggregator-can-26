package ca.northline.merchants.web;

import ca.northline.merchants.application.StorefrontUseCases.ViewPublishedStorefront;
import ca.northline.merchants.web.StorefrontDtos.PublicStorefrontResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Public read of published pages ({@code /api/v1/storefronts/**} is open in SecurityConfig): consumer web and app. */
@RestController
@RequiredArgsConstructor
class PublicStorefrontController {

    private final ViewPublishedStorefront published;
    private final StorefrontWebMapper mapper;

    @GetMapping("/api/v1/storefronts/{slug}")
    PublicStorefrontResponse get(@PathVariable String slug) {
        return mapper.toPublic(published.bySlug(slug));
    }

    @GetMapping("/api/v1/storefronts/{slug}/logo")
    ResponseEntity<byte[]> logo(@PathVariable String slug) {
        return OnboardingDocumentController.file(published.logo(slug));
    }
}
