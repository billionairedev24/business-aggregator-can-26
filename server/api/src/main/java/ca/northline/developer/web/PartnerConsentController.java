package ca.northline.developer.web;

import static ca.northline.shared.security.MerchantPermission.MANAGE;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.developer.api.PartnerClients;
import ca.northline.developer.api.PartnerClients.Consent;
import ca.northline.shared.ListResponse;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settings › API › Partner access (S-158): the partners Northline registered that asked to act for this business, and
 * the owner's answer. Only approved access reaches the partner's tokens (within northline-auth's refresh and the
 * token's lifetime). Audit-logged under the business.
 *
 * <pre>
 * GET  /api/v1/merchants/{merchantId}/settings/partners                                   {items: [Consent]}
 * POST /api/v1/merchants/{merchantId}/settings/partners/{partnerId}/decision {decision: approve|decline|revoke}
 *      owner; 409 partner_not_requested | partner_decided | partner_not_approved
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/settings/partners")
@RequiredArgsConstructor
class PartnerConsentController {

    private final PartnerClients partners;

    record DecisionRequest(
            @NotBlank(message = PartnerClients.DECISION)
            @Pattern(regexp = "approve|decline|revoke", message = PartnerClients.DECISION)
            String decision) {}

    @GetMapping
    @RequiresMerchant(VIEW)
    ListResponse<Consent> consents(@PathVariable String merchantId) {
        return new ListResponse<>(partners.consents(merchantId));
    }

    @PostMapping("/{partnerId}/decision")
    @RequiresMerchant(MANAGE)
    Consent decide(
            @PathVariable String merchantId,
            @PathVariable String partnerId,
            @Valid @RequestBody DecisionRequest body,
            CurrentMember member) {
        return partners.decide(
                merchantId,
                partnerId,
                body.decision(),
                member.userId(),
                member.role().code());
    }
}
