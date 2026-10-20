package ca.northline.loyalty.adapters;

import ca.northline.loyalty.application.LoyaltyUseCases.LoyaltyJobs;
import java.util.function.IntSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the loyalty jobs on {@code LOYALTY_JOBS_CRON} (default hourly at :07, platform zone; not under {@code test}:
 * tests call {@link LoyaltyJobs} themselves): points that expired, referrals whose window passed, the local fake's Plus
 * renewals. One failing step is logged and retried on the next run.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
@RequiredArgsConstructor
class LoyaltyScheduler {

    private final LoyaltyJobs jobs;

    @Scheduled(cron = "${northline.loyalty.jobs-cron:0 7 * * * *}", zone = "${northline.region.platform-zone}")
    void run() {
        step("expire points", jobs::expirePoints);
        step("close referrals", jobs::closeReferrals);
        step("renew local Plus", jobs::renewLocalMemberships);
    }

    private static void step(String name, IntSupplier work) {
        try {
            var changed = work.getAsInt();
            if (changed > 0) {
                log.info("Loyalty job '{}' changed {} record(s)", name, changed);
            }
        } catch (RuntimeException e) {
            log.error("Loyalty job '{}' failed; retrying next run", name, e);
        }
    }
}
