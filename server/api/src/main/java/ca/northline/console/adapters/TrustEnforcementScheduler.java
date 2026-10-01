package ca.northline.console.adapters;

import ca.northline.console.application.EnforceTrustRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** S-82: the trust rules' consequences nightly, after the quality scores (not under {@code test}). */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
@RequiredArgsConstructor
class TrustEnforcementScheduler {

    private final EnforceTrustRules enforce;

    @Scheduled(
            cron = "${northline.console.trust-enforcement-cron:0 23 5 * * *}",
            zone = "${northline.region.platform-zone}")
    void nightly() {
        try {
            enforce.run();
        } catch (RuntimeException e) {
            log.error("Trust rule enforcement failed; retried tomorrow", e);
        }
    }
}
