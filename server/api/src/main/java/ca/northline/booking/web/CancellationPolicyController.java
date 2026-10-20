package ca.northline.booking.web;

import static ca.northline.shared.security.MerchantPermission.MANAGE;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.booking.application.ManageCancellationPolicy;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-153, Studio › Availability › Cancellations: the late-cancellation policy customers see before booking. Owners
 * change it; every member reads it. The region's default and limits come with it.
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/cancellation-policy")
@RequiredArgsConstructor
class CancellationPolicyController {

    static final String REQUIRED = "Choose a window and a fee.";

    private final ManageCancellationPolicy policies;

    record PolicyRequest(
            @NotNull(message = REQUIRED) Integer windowHours,
            @NotNull(message = REQUIRED) Integer feeBps) {}

    record Limits(int maxWindowHours, int maxFeeBps) {}

    record Defaults(int windowHours, int feeBps) {}

    record PolicyResponse(int windowHours, int feeBps, boolean custom, Defaults regionDefault, Limits limits) {
        static PolicyResponse of(ManageCancellationPolicy.View v) {
            return new PolicyResponse(
                    v.policy().windowHours(),
                    v.policy().feeBps(),
                    v.custom(),
                    new Defaults(v.region().windowHours(), v.region().feeBps()),
                    new Limits(v.region().maxWindowHours(), v.region().maxFeeBps()));
        }
    }

    @GetMapping
    @RequiresMerchant(VIEW)
    PolicyResponse get(@PathVariable String merchantId) {
        return PolicyResponse.of(policies.view(merchantId));
    }

    @PutMapping
    @RequiresMerchant(MANAGE)
    PolicyResponse put(@PathVariable String merchantId, @Valid @RequestBody PolicyRequest body, CurrentMember member) {
        return PolicyResponse.of(policies.set(merchantId, member.userId(), body.windowHours(), body.feeBps()));
    }

    @DeleteMapping
    @RequiresMerchant(MANAGE)
    PolicyResponse reset(@PathVariable String merchantId) {
        return PolicyResponse.of(policies.reset(merchantId));
    }
}
