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
 * Runs the payments jobs every minute (not under {@code test}: tests call {@link PaymentsJobs} themselves). One failing
 * step is logged and retried on the next run; it never blocks the others.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
@RequiredArgsConstructor
class PaymentsScheduler {

    private final PaymentsJobs jobs;

    @Scheduled(fixedDelayString = "${northline.payments.jobs-interval:PT1M}", initialDelayString = "PT20S")
    void run() {
        step("release escrow", jobs::releaseDueEscrows);
        step("stripe events", jobs::processStripeEvents);
        step("renew card holds", jobs::renewAuthorizations);
        step("lapse cases", jobs::lapseCases);
        step("refund queue", jobs::payRefundQueue);
        step("payout accounts", jobs::activatePayoutAccounts);
        step("payouts", jobs::runPayouts);
    }

    private static void step(String name, IntSupplier job) {
        try {
            var changed = job.getAsInt();
            if (changed > 0) {
                log.info("Payments job '{}' changed {} record(s)", name, changed);
            }
        } catch (RuntimeException e) {
            log.error("Payments job '{}' failed; retrying next run", name, e);
        }
    }
}
