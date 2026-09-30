package ca.northline.payments.web;

import ca.northline.payments.application.ReconcileTax;
import ca.northline.shared.security.CurrentUser;
import ca.northline.shared.security.MerchantAccessDenied;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform finance (staff, {@code /api/v1/console/**} needs role {@code STAFF}; a second factor too): runs the Stripe
 * Tax reconciliation of a quarter now — the same as the nightly job (docs/runbooks/stripe.md § 6).
 *
 * <pre>
 * POST /api/v1/console/payments/tax-reconciliations   { "period": "2026-Q3" }   (period optional: this quarter)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/payments/tax-reconciliations")
@RequiredArgsConstructor
class TaxReconciliationController {

    private final ReconcileTax reconcile;

    record ReconcileRequest(@Nullable String period) {}

    @PostMapping
    ReconcileTax.Report run(@RequestBody(required = false) @Nullable ReconcileRequest body, CurrentUser user) {
        if (!user.mfa()) {
            throw new MerchantAccessDenied(
                    MerchantAccessDenied.Reason.MFA_REQUIRED, "Sign in with your second factor to do this.");
        }
        return reconcile.reconcile(body == null ? null : body.period());
    }
}
