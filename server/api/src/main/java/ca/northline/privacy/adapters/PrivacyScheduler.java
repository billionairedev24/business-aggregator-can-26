package ca.northline.privacy.adapters;

import ca.northline.privacy.application.PrivacyWork;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the privacy pipeline every {@code PRIVACY_RUN_INTERVAL} (default a minute) and deletes expired exports hourly.
 * Replicas share the work (steps are claimed {@code FOR UPDATE SKIP LOCKED}). Not under {@code test} (tests call
 * {@link PrivacyWork} directly).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
@RequiredArgsConstructor
class PrivacyScheduler {

    private final PrivacyWork work;

    @Scheduled(fixedDelayString = "${northline.privacy.run-every:PT1M}", initialDelayString = "PT30S")
    void run() {
        try {
            var completed = work.runDue();
            if (completed > 0) {
                log.info("Privacy pipeline: {} request(s) completed", completed);
            }
        } catch (RuntimeException e) {
            log.error("Privacy pipeline failed; retrying on the next run", e);
        }
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    void sweep() {
        try {
            var swept = work.sweep();
            if (swept > 0) {
                log.info("Privacy exports deleted after expiry: {}", swept);
            }
        } catch (RuntimeException e) {
            log.error("Privacy export sweep failed", e);
        }
    }
}
