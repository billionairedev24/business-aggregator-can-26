package ca.northline.merchants.web;

import static ca.northline.shared.security.MerchantPermission.MANAGE;

import ca.northline.merchants.application.OwnerIdentity.ListOwners;
import ca.northline.merchants.application.OwnerIdentity.OwnerView;
import ca.northline.merchants.application.OwnerIdentity.StartOwnerVerification;
import ca.northline.merchants.domain.OwnerIdentityCheck.Delivery;
import ca.northline.shared.ListResponse;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identity verification of the owners (S-22) — the Verification step's "Identity (Stripe KYC)" row. Owner-only: it
 * shows the owners' names and sends links.
 *
 * <pre>
 * GET  /api/v1/merchants/{merchantId}/identity-checks                                      {items: [OwnerView]}
 * POST /api/v1/merchants/{merchantId}/identity-checks/{principalId}/session  {delivery: self|email, email?}
 *      → {owner, url} — url = Stripe's hosted flow for self, null when the link was emailed
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/identity-checks")
@RequiredArgsConstructor
class IdentityCheckController {

    static final String DELIVERY_REQUIRED = "Choose how this owner verifies.";

    private final ListOwners owners;
    private final StartOwnerVerification start;

    record StartSessionRequest(
            @NotBlank(message = DELIVERY_REQUIRED) @Pattern(regexp = "self|email", message = DELIVERY_REQUIRED)
            String delivery,

            @Nullable String email) {}

    record StartSessionResponse(OwnerView owner, @Nullable String url) {}

    @GetMapping
    @RequiresMerchant(MANAGE)
    ListResponse<OwnerView> list(@PathVariable String merchantId, CurrentMember member) {
        return new ListResponse<>(owners.owners(merchantId, member.userId()));
    }

    @PostMapping("/{principalId}/session")
    @RequiresMerchant(MANAGE)
    StartSessionResponse session(
            @PathVariable String merchantId,
            @PathVariable String principalId,
            @Valid @RequestBody StartSessionRequest body,
            CurrentMember member) {
        var delivery = "self".equals(body.delivery()) ? Delivery.SELF : Delivery.EMAIL;
        var started = start.start(
                new StartOwnerVerification.Command(merchantId, principalId, delivery, body.email(), member.userId()));
        return new StartSessionResponse(started.owner(), started.url());
    }
}
