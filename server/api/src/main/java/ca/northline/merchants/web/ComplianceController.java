package ca.northline.merchants.web;

import static ca.northline.shared.security.MerchantPermission.MANAGE;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.merchants.application.ComplianceUseCases.AcceptObligations;
import ca.northline.merchants.application.ComplianceUseCases.ComplianceView;
import ca.northline.merchants.application.ComplianceUseCases.ObligationsView;
import ca.northline.merchants.application.ComplianceUseCases.OpenStripeLink;
import ca.northline.merchants.application.ComplianceUseCases.RenewVerification;
import ca.northline.merchants.application.ComplianceUseCases.ViewCompliance;
import ca.northline.merchants.application.Documents;
import ca.northline.merchants.domain.ComplianceItem;
import ca.northline.merchants.domain.ComplianceRules;
import ca.northline.merchants.web.SettingsDtos.AcceptObligationsRequest;
import ca.northline.merchants.web.SettingsDtos.LinkResponse;
import ca.northline.merchants.web.SettingsDtos.StripeLinkRequest;
import ca.northline.shared.Bytes;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Stripe &amp; compliance. The whole team can read it; uploads, accepting obligations and Stripe links are owner-only.
 *
 * <pre>
 * GET  /api/v1/merchants/{merchantId}/compliance                                         (VIEW)
 * POST /api/v1/merchants/{merchantId}/compliance/verifications/{id}/renewal  multipart file   (MANAGE)
 * POST /api/v1/merchants/{merchantId}/compliance/obligations   {version}                  (MANAGE)
 * POST /api/v1/merchants/{merchantId}/compliance/stripe-links  {kind: dashboard|update}   (MANAGE)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/compliance")
@RequiredArgsConstructor
class ComplianceController {

    private final ViewCompliance view;
    private final RenewVerification renew;
    private final AcceptObligations obligations;
    private final OpenStripeLink stripeLinks;

    @GetMapping
    @RequiresMerchant(VIEW)
    ComplianceView compliance(@PathVariable String merchantId) {
        return view.view(merchantId);
    }

    @PostMapping(path = "/verifications/{verificationId}/renewal", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresMerchant(MANAGE)
    ComplianceItem renew(
            @PathVariable String merchantId,
            @PathVariable String verificationId,
            @RequestPart(name = "file", required = false) @Nullable MultipartFile file,
            CurrentMember member) {
        if (file == null || file.isEmpty()) {
            throw RuleViolation.of(Documents.FIELD, "required", ComplianceRules.DOCUMENT_REQUIRED);
        }
        try {
            return renew.renew(new RenewVerification.Command(
                    SettingsController.actor(member),
                    verificationId,
                    Objects.requireNonNullElse(file.getOriginalFilename(), "document"),
                    Objects.requireNonNullElse(file.getContentType(), "application/octet-stream"),
                    Bytes.of(file.getBytes())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @PostMapping("/obligations")
    @RequiresMerchant(MANAGE)
    ObligationsView acceptObligations(
            @PathVariable String merchantId, @Valid @RequestBody AcceptObligationsRequest body, CurrentMember member) {
        return obligations.accept(SettingsController.actor(member), body.version());
    }

    @PostMapping("/stripe-links")
    @RequiresMerchant(MANAGE)
    LinkResponse stripeLink(
            @PathVariable String merchantId, @Valid @RequestBody StripeLinkRequest body, CurrentMember member) {
        return new LinkResponse(stripeLinks.link(SettingsController.actor(member), body.kind()));
    }
}
