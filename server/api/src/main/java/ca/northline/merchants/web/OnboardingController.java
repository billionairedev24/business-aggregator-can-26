package ca.northline.merchants.web;

import static ca.northline.shared.security.MerchantPermission.MANAGE;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.merchants.application.CompleteVerification;
import ca.northline.merchants.application.ManageApplication.AdvanceOnboarding;
import ca.northline.merchants.application.ManageApplication.SubmitApplication;
import ca.northline.merchants.application.ManageApplication.ViewOnboarding;
import ca.northline.merchants.application.SaveBusiness;
import ca.northline.merchants.application.StartOnboarding;
import ca.northline.merchants.application.UpdateAccount;
import ca.northline.merchants.domain.BusinessProfile;
import ca.northline.merchants.web.OnboardingRequests.AccountRequest;
import ca.northline.merchants.web.OnboardingRequests.BusinessRequest;
import ca.northline.merchants.web.OnboardingRequests.CompleteRequest;
import ca.northline.merchants.web.OnboardingRequests.StepRequest;
import ca.northline.merchants.web.OnboardingResponses.OnboardingResponse;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.CurrentUser;
import ca.northline.shared.security.MerchantAccessDenied;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Business onboarding wizard (design 02, onboarding): Account → Business → Verification → Review → page → listings.
 * The applicant is created at the end of the Account step ({@code POST /api/v1/merchants}); every later step is an
 * owner-only write on {@code /api/v1/merchants/{merchantId}/onboarding…}.
 */
@RestController
@RequiredArgsConstructor
class OnboardingController {

    private final StartOnboarding startOnboarding;
    private final UpdateAccount updateAccount;
    private final SaveBusiness saveBusiness;
    private final ViewOnboarding viewOnboarding;
    private final AdvanceOnboarding advanceOnboarding;
    private final SubmitApplication submitApplication;
    private final CompleteVerification completeVerification;
    private final OnboardingWebMapper mapper;

    /** End of the Account step. Needs a second factor like every business action ({@code acr=mfa}). */
    @PostMapping("/api/v1/merchants")
    @ResponseStatus(HttpStatus.CREATED)
    OnboardingResponse start(@Valid @RequestBody AccountRequest body, CurrentUser user) {
        if (!user.mfa()) {
            throw new MerchantAccessDenied(
                    MerchantAccessDenied.Reason.MFA_REQUIRED,
                    "Business access needs a second factor. Sign in again with your passkey or authenticator app.");
        }
        return mapper.toResponse(startOnboarding.start(new StartOnboarding.Command(
                user.userId(),
                body.type(),
                body.province(),
                body.workEmail(),
                Boolean.TRUE.equals(body.businessTermsAccepted()))));
    }

    @GetMapping("/api/v1/merchants/{merchantId}/onboarding")
    @RequiresMerchant(VIEW)
    OnboardingResponse get(@PathVariable String merchantId) {
        return mapper.toResponse(viewOnboarding.view(merchantId));
    }

    @PutMapping("/api/v1/merchants/{merchantId}/onboarding/account")
    @RequiresMerchant(MANAGE)
    OnboardingResponse account(@PathVariable String merchantId, @Valid @RequestBody AccountRequest body) {
        return mapper.toResponse(updateAccount.update(new UpdateAccount.Command(
                merchantId,
                body.type(),
                body.province(),
                body.workEmail(),
                Boolean.TRUE.equals(body.businessTermsAccepted()))));
    }

    @PutMapping("/api/v1/merchants/{merchantId}/onboarding/business")
    @RequiresMerchant(MANAGE)
    OnboardingResponse business(@PathVariable String merchantId, @Valid @RequestBody BusinessRequest body) {
        return mapper.toResponse(saveBusiness.save(new SaveBusiness.Command(
                merchantId,
                body.displayName(),
                body.legalName(),
                body.structure(),
                body.gstNumber(),
                Objects.requireNonNullElse(body.legalDetails(), Map.of()),
                mapper.fromRequests(Objects.requireNonNullElse(body.principals(), List.of())),
                Objects.requireNonNullElse(body.categoryIds(), List.of()),
                Objects.requireNonNullElse(body.suggestedCategories(), List.of()),
                body.profile() == null ? BusinessProfile.EMPTY : mapper.toProfile(body.profile()))));
    }

    @PatchMapping("/api/v1/merchants/{merchantId}/onboarding")
    @RequiresMerchant(MANAGE)
    OnboardingResponse step(@PathVariable String merchantId, @Valid @RequestBody StepRequest body) {
        return mapper.toResponse(advanceOnboarding.advance(merchantId, body.step()));
    }

    @PostMapping("/api/v1/merchants/{merchantId}/onboarding/submit")
    @RequiresMerchant(MANAGE)
    OnboardingResponse submit(@PathVariable String merchantId, CurrentMember member) {
        return mapper.toResponse(submitApplication.submit(merchantId, member.userId()));
    }

    @PostMapping("/api/v1/merchants/{merchantId}/verifications/{verificationId}/complete")
    @RequiresMerchant(MANAGE)
    OnboardingResponse complete(
            @PathVariable String merchantId,
            @PathVariable String verificationId,
            @Valid @RequestBody CompleteRequest body) {
        return mapper.toResponse(completeVerification.complete(new CompleteVerification.Command(
                merchantId, verificationId, body.reference(), body.documentId(), body.expiresOn(), body.choice())));
    }
}
