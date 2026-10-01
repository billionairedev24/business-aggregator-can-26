package ca.northline.merchants.web;

import ca.northline.merchants.api.SellerDirectory.Oversight;
import ca.northline.merchants.application.SellerOversight;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Oversight actions on a business from the console's seller detail (S-82, design 03 {@code seller_detail}). Screen
 * sellers; suspending, reinstating and changing the tier need the {@code suspend} action (admin, trust &amp; safety),
 * asking for a check again {@code verify}. Each is audited and emailed to the business's owners with the reason.
 *
 * <pre>
 * POST /api/v1/console/merchants/{businessId}/suspend        {reason}                  409 not_active
 * POST /api/v1/console/merchants/{businessId}/reinstate      {reason}                  409 not_suspended
 * POST /api/v1/console/merchants/{businessId}/reverification {verificationId, reason}  409 not_verifiable
 * POST /api/v1/console/merchants/{businessId}/tier           {tier, reason}            409 same_tier · not_approved
 * → Oversight {id, action, reason, detail, actorId, actorRole, at}
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/merchants/{businessId}")
@RequiredArgsConstructor
class SellerOversightController {

    private final SellerOversight oversight;

    record ReasonRequest(
            @NotBlank(message = SellerOversight.REASON_REQUIRED)
            @Size(max = 500, message = SellerOversight.REASON_LENGTH)
            String reason) {}

    record ReverificationRequest(
            @NotBlank(message = SellerOversight.CHECK_REQUIRED)
            String verificationId,

            @NotBlank(message = SellerOversight.REASON_REQUIRED)
            @Size(max = 500, message = SellerOversight.REASON_LENGTH)
            String reason) {}

    record TierRequest(
            @NotBlank(message = SellerOversight.TIER) String tier,

            @NotBlank(message = SellerOversight.REASON_REQUIRED)
            @Size(max = 500, message = SellerOversight.REASON_LENGTH)
            String reason) {}

    @PostMapping("/suspend")
    @RequiresConsole(value = ConsoleScreen.SELLERS, actions = ConsoleAction.SUSPEND)
    Oversight suspend(@PathVariable String businessId, @Valid @RequestBody ReasonRequest body, CurrentStaff staff) {
        return oversight.suspend(businessId, body.reason(), actor(staff));
    }

    @PostMapping("/reinstate")
    @RequiresConsole(value = ConsoleScreen.SELLERS, actions = ConsoleAction.SUSPEND)
    Oversight reinstate(@PathVariable String businessId, @Valid @RequestBody ReasonRequest body, CurrentStaff staff) {
        return oversight.reinstate(businessId, body.reason(), actor(staff));
    }

    @PostMapping("/reverification")
    @RequiresConsole(value = ConsoleScreen.SELLERS, actions = ConsoleAction.VERIFY)
    Oversight reverification(
            @PathVariable String businessId, @Valid @RequestBody ReverificationRequest body, CurrentStaff staff) {
        return oversight.requireReverification(businessId, body.verificationId(), body.reason(), actor(staff));
    }

    @PostMapping("/tier")
    @RequiresConsole(value = ConsoleScreen.SELLERS, actions = ConsoleAction.SUSPEND)
    Oversight tier(@PathVariable String businessId, @Valid @RequestBody TierRequest body, CurrentStaff staff) {
        return oversight.changeTier(businessId, body.tier(), body.reason(), actor(staff));
    }

    private static SellerOversight.Actor actor(CurrentStaff staff) {
        return new SellerOversight.Actor(staff.userId(), staff.roleCodes());
    }
}
