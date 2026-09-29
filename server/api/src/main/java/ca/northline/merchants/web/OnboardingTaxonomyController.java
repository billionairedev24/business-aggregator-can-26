package ca.northline.merchants.web;

import ca.northline.merchants.application.BrowseTaxonomy;
import ca.northline.merchants.domain.MerchantType;
import ca.northline.merchants.web.OnboardingResponses.TaxonomyResponse;
import ca.northline.shared.CodedEnum;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Business step category picker: {@code GET /api/v1/onboarding/taxonomy?type=provider|seller|kitchen|both} → groups
 * with their leaves, regulators and the per-type limit. Any signed-in user (the applicant may not exist yet).
 */
@RestController
@RequiredArgsConstructor
class OnboardingTaxonomyController {

    private final BrowseTaxonomy browseTaxonomy;
    private final OnboardingWebMapper mapper;

    @GetMapping("/api/v1/onboarding/taxonomy")
    TaxonomyResponse taxonomy(@RequestParam String type, Locale locale) {
        MerchantType merchantType;
        try {
            merchantType = CodedEnum.fromCode(MerchantType.class, type);
        } catch (IllegalArgumentException _) {
            throw ca.northline.shared.RuleViolation.of("type", "enum", "Pick provider, seller, kitchen or both.");
        }
        return mapper.toResponse(browseTaxonomy.forType(merchantType), locale);
    }
}
