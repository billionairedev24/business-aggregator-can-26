package ca.northline.merchants.web;

import static ca.northline.shared.security.MerchantPermission.MANAGE;

import ca.northline.merchants.application.StorefrontUseCases.SimulateDomainRecords;
import ca.northline.merchants.web.StorefrontDtos.StorefrontResponse;
import ca.northline.shared.security.RequiresMerchant;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LOCAL PROFILE ONLY — backs the Studio's "Simulate DNS records →" (dev builds): writes the custom domain's CNAME (or
 * apex A) and TXT records into the in-memory DNS zone, checks them, and reconciles the local edge, so the domain goes
 * verified → live without a DNS host (S-31).
 */
@RestController
@Profile("local")
@RequiredArgsConstructor
class DevDomainController {

    private final SimulateDomainRecords simulate;
    private final StorefrontWebMapper mapper;

    @PostMapping("/api/v1/dev/merchants/{merchantId}/storefront/domain/dns")
    @RequiresMerchant(MANAGE)
    StorefrontResponse simulate(@PathVariable String merchantId) {
        return mapper.toResponse(simulate.simulate(merchantId));
    }
}
