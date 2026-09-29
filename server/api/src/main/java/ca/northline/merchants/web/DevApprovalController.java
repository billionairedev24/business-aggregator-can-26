package ca.northline.merchants.web;

import static ca.northline.shared.security.MerchantPermission.MANAGE;

import ca.northline.merchants.application.ManageApplication.ApproveApplication;
import ca.northline.merchants.web.OnboardingResponses.OnboardingResponse;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LOCAL PROFILE ONLY — backs the design's "Simulate approval →" button: the owner approves their own pending
 * application ({@code merchant.approved}). Real approvals come from the Platform Console's verification queue.
 */
@RestController
@Profile("local")
@RequiredArgsConstructor
class DevApprovalController {

    private final ApproveApplication approveApplication;
    private final OnboardingWebMapper mapper;

    @PostMapping("/api/v1/dev/merchants/{merchantId}/approve")
    @RequiresMerchant(MANAGE)
    OnboardingResponse approve(@PathVariable String merchantId, CurrentMember member) {
        return mapper.toResponse(approveApplication.approve(merchantId, member.userId()));
    }
}
