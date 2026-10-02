package ca.northline.privacy.adapters;

import ca.northline.privacy.application.Retention;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * S-107: the nightly retention run, at {@code RETENTION_CRON} in the platform zone (region model). Every replica fires;
 * a category another replica already ran tonight is skipped and batches run under the category's advisory lock. Off
 * with {@code RETENTION_ENABLED=false}, and under {@code test} (tests call {@link Retention.Work} directly).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
@ConditionalOnProperty(name = "northline.retention.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
class RetentionScheduler {

    private final Retention.Work work;

    @Scheduled(cron = "${northline.retention.cron:0 47 2 * * *}", zone = "${northline.region.platform-zone}")
    void run() {
        try {
            var ran = work.runScheduled();
            if (ran > 0) {
                log.info("Retention: {} categories ran", ran);
            }
        } catch (RuntimeException e) {
            log.error("Retention run failed; it runs again tomorrow", e);
        }
    }
}
