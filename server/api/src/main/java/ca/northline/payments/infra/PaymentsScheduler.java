package ca.northline.payments.infra;

import ca.northline.payments.application.PaymentsJobs;
import java.util.function.IntSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the payments jobs every minute and the Stripe Tax reconciliation nightly (not under {@code test}: tests call
 * {@link PaymentsJobs} themselves). One failing step is logged and retried on the next run; it never blocks the others.
 * S-113: every step's outcome is counted by {@link JobRuns} (the payout run SLO, docs/runbooks/alerting.md).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
@RequiredArgsConstructor
class PaymentsScheduler {

    private final PaymentsJobs jobs;
    private final JobRuns runs;

    @Scheduled(fixedDelayString = "${northline.payments.jobs-interval:PT1M}", initialDelayString = "PT20S")
    void run() {
        step("release escrow", "payments.release_escrow", jobs::releaseDueEscrows);
        step("stripe events", "payments.stripe_events", jobs::processStripeEvents);
        step("renew card holds", "payments.renew_holds", jobs::renewAuthorizations);
        step("lapse cases", "payments.lapse_cases", jobs::lapseCases);
        step("refund queue", "payments.refund_queue", jobs::payRefundQueue);
        step("stripe tax", "payments.stripe_tax", jobs::syncTax);
        step("payout accounts", "payments.payout_accounts", jobs::activatePayoutAccounts);
        step("payouts", JobRuns.PAYOUTS, jobs::runPayouts);
    }

    /** Nightly at 03:17 in the platform zone (REGION_PLATFORM_ZONE): the Stripe Tax reconciliation (S-21). */
    @Scheduled(cron = "${northline.tax.reconcile-cron:0 17 3 * * *}", zone = "${northline.region.platform-zone}")
    void reconcileTax() {
        step("stripe tax reconciliation", "payments.tax_reconciliation", jobs::reconcileTax);
    }

    private void step(String name, String job, IntSupplier work) {
        try {
            var changed = work.getAsInt();
            runs.succeeded(job);
            if (changed > 0) {
                log.info("Payments job '{}' changed {} record(s)", name, changed);
            }
        } catch (RuntimeException e) {
            runs.failed(job);
            log.error("Payments job '{}' failed; retrying next run", name, e);
        }
    }
}
