package ca.northline.payments.web;

import static ca.northline.shared.security.MerchantPermission.MANAGE;

import ca.northline.payments.application.RunPayouts;
import ca.northline.shared.security.RequiresMerchant;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LOCAL PROFILE ONLY (S-117) — the end-to-end suite's "payout run": the scheduled run for the owner's business now,
 * whatever day and time it is. 201 with the payout, 204 when nothing is payable. The route doesn't exist under any
 * other profile (DevOnlyRoutesTest checks it under {@code prod}).
 */
@RestController
@Profile("local")
@RequiredArgsConstructor
class DevPayoutRunController {

    private final RunPayouts payouts;
    private final PaymentsWebMapper mapper;

    @PostMapping("/api/v1/dev/merchants/{merchantId}/payouts/run")
    @RequiresMerchant(MANAGE)
    ResponseEntity<PayoutResponses.PayoutLine> run(@PathVariable String merchantId) {
        return payouts.runNow(merchantId)
                .map(p -> ResponseEntity.status(201).body(mapper.toResponse(p)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
