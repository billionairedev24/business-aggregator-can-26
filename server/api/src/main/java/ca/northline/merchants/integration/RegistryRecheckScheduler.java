package ca.northline.merchants.integration;

import ca.northline.merchants.application.RecheckRegistries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Re-checks verified registry and licence rows (S-23; the design's "Registry checks re-run monthly"): daily at 03:41
 * Calgary time by default ({@code REGISTRY_RECHECK_CRON}), each row once {@code REGISTRY_RECHECK_AFTER} (30 days)
 * passed. Replicas share the work (rows are claimed {@code FOR UPDATE SKIP LOCKED}). Not under {@code test}.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
@RequiredArgsConstructor
class RegistryRecheckScheduler {

    static final int MAX_BATCHES = 40;

    private final RecheckRegistries rechecks;

    @Scheduled(cron = "${northline.registries.recheck-cron:0 41 3 * * *}", zone = "America/Edmonton")
    void run() {
        try {
            // batches of 50 until a batch comes back short; rows whose source was down stay due, hence the bound
            var total = 0;
            for (var batch = 0; batch < MAX_BATCHES; batch++) {
                var n = rechecks.recheckDue();
                total += n;
                if (n < RecheckRegistries.BATCH) {
                    break;
                }
            }
            if (total > 0) {
                log.info("Registry re-check: {} row(s)", total);
            }
        } catch (RuntimeException e) {
            log.error("Registry re-check failed; retrying tomorrow", e);
        }
    }
}
