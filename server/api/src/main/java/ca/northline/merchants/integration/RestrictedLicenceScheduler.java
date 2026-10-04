package ca.northline.merchants.integration;

import ca.northline.merchants.application.RestrictedLicenceUseCases.LicenceJobs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Licences for age-restricted sales: daily at 00:17 the platform zone ({@code RESTRICTED_LICENCE_CRON}) approved
 * licences past their expiry become expired (the business's restricted listings are hidden), and owners are reminded
 * 30 days before an expiry. Replicas share the work ({@code FOR UPDATE SKIP LOCKED}). Not under {@code test}.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
@RequiredArgsConstructor
class RestrictedLicenceScheduler {

    static final int MAX_BATCHES = 20;

    private final LicenceJobs jobs;

    @Scheduled(cron = "${northline.restricted.licence-cron:0 17 0 * * *}", zone = "${northline.region.platform-zone}")
    void run() {
        try {
            var expired = 0;
            var reminded = 0;
            for (var batch = 0; batch < MAX_BATCHES; batch++) {
                var e = jobs.expireDue();
                var r = jobs.remindDue();
                expired += e;
                reminded += r;
                if (e == 0 && r == 0) {
                    break;
                }
            }
            if (expired + reminded > 0) {
                log.info("Restricted licences: {} expired, {} reminded", expired, reminded);
            }
        } catch (RuntimeException e) {
            log.error("Restricted licence job failed; retrying tomorrow", e);
        }
    }
}
