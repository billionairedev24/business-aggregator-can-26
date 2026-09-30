package ca.northline.catalogue.adapters.commerce;

import ca.northline.catalogue.application.SyncIntegrations.CommerceJobs;
import java.util.function.IntSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * S-35 scheduled reads: every 5 minutes the connections that are due are read in full — without webhooks every
 * {@code COMMERCE_POLL_INTERVAL} (1 h: "stock changes sync hourly"), with them every {@code
 * COMMERCE_RECONCILE_INTERVAL} (1 day: missed deliveries, deletions). Hourly, expired OAuth requests and week-old
 * webhook receipts are purged. Not under {@code test} (tests call {@link CommerceJobs}). Replicas share the work: a
 * read is claimed by moving {@code last_polled_at}, so one replica wins.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
@RequiredArgsConstructor
class CommerceScheduler {

    private final CommerceJobs jobs;

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    void sync() {
        step("sync", jobs::syncDue);
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT3M")
    void purge() {
        step("purge", jobs::purge);
    }

    private static void step(String name, IntSupplier job) {
        try {
            var count = job.getAsInt();
            if (count > 0) {
                log.info("Commerce job '{}': {}", name, count);
            }
        } catch (RuntimeException e) {
            log.error("Commerce job '{}' failed; retrying next run", name, e);
        }
    }
}
