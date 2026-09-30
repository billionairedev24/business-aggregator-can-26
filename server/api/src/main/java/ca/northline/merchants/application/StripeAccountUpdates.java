package ca.northline.merchants.application;

import ca.northline.payments.api.ConnectAccountUpdated;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Stripe reported a change on a business's Connect account (S-12 {@code account.updated}): make sure the business
 * points at it (an account created from the Stripe dashboard, or a link that didn't save), so Settings › Stripe &amp;
 * compliance reads its live requirements. Idempotent: an already-linked business keeps its account.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class StripeAccountUpdates {

    private final ComplianceLedgerStore ledger;

    @ApplicationModuleListener
    void on(ConnectAccountUpdated event) {
        ledger.linkStripeAccount(event.aggregateId(), event.stripeAccount());
        if (event.requirementsPastDue() > 0 || !event.payoutsEnabled()) {
            log.info(
                    "Stripe account {} of {}: payouts {}, {} requirement(s) past due ({})",
                    event.stripeAccount(),
                    event.aggregateId(),
                    event.payoutsEnabled() ? "on" : "paused",
                    event.requirementsPastDue(),
                    event.disabledReason());
        }
    }
}
