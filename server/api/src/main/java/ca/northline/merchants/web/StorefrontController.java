package ca.northline.merchants.web;

import static ca.northline.shared.security.MerchantPermission.MANAGE;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.merchants.application.StorefrontUseCases.ArrangeSections;
import ca.northline.merchants.application.StorefrontUseCases.CreateStorefront;
import ca.northline.merchants.application.StorefrontUseCases.PublishStorefront;
import ca.northline.merchants.application.StorefrontUseCases.UpdateStorefront;
import ca.northline.merchants.application.StorefrontUseCases.VerifyCustomDomain;
import ca.northline.merchants.application.StorefrontUseCases.ViewStorefront;
import ca.northline.merchants.web.StorefrontDtos.SectionsRequest;
import ca.northline.merchants.web.StorefrontDtos.StorefrontResponse;
import ca.northline.merchants.web.StorefrontDtos.UpdateRequest;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Page builder: {@code /api/v1/merchants/{merchantId}/storefront}. Reads for every member, edits for the owner
 * ({@code MANAGE}: "business profile, storefront, team").
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/storefront")
@RequiredArgsConstructor
class StorefrontController {

    private final ViewStorefront viewStorefront;
    private final CreateStorefront createStorefront;
    private final UpdateStorefront updateStorefront;
    private final ArrangeSections arrangeSections;
    private final VerifyCustomDomain verifyCustomDomain;
    private final PublishStorefront publishStorefront;
    private final StorefrontWebMapper mapper;

    @GetMapping
    @RequiresMerchant(VIEW)
    StorefrontResponse get(@PathVariable String merchantId) {
        return mapper.toResponse(viewStorefront.view(merchantId));
    }

    /** Creates the page with the recommended sections; returns the existing one when there is one. */
    @PostMapping
    @RequiresMerchant(MANAGE)
    StorefrontResponse create(@PathVariable String merchantId) {
        return mapper.toResponse(createStorefront.create(merchantId));
    }

    @PatchMapping
    @RequiresMerchant(MANAGE)
    StorefrontResponse update(@PathVariable String merchantId, @RequestBody UpdateRequest body) {
        return mapper.toResponse(updateStorefront.update(new UpdateStorefront.Command(
                merchantId,
                body.brandColor(),
                body.tagline(),
                body.ctaLabel(),
                body.announcement(),
                body.customDomain(),
                body.slug(),
                body.logoDocumentId())));
    }

    /** Reorder and toggle in one call: the full ordered list of the page's sections. */
    @PatchMapping("/sections")
    @RequiresMerchant(MANAGE)
    StorefrontResponse sections(@PathVariable String merchantId, @Valid @RequestBody SectionsRequest body) {
        return mapper.toResponse(arrangeSections.arrange(merchantId, mapper.toStates(body.sections())));
    }

    @PostMapping("/domain/verify")
    @RequiresMerchant(MANAGE)
    StorefrontResponse verifyDomain(@PathVariable String merchantId) {
        return mapper.toResponse(verifyCustomDomain.verify(merchantId));
    }

    @PostMapping("/publish")
    @RequiresMerchant(MANAGE)
    StorefrontResponse publish(@PathVariable String merchantId, CurrentMember member) {
        return mapper.toResponse(publishStorefront.publish(merchantId, member.userId()));
    }
}
