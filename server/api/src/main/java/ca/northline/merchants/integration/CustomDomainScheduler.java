package ca.northline.merchants.integration;

import ca.northline.merchants.application.StorefrontUseCases.CustomDomainJobs;
import java.util.function.IntSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * S-31: due DNS checks every {@code DOMAINS_CHECK_INTERVAL} (1 min; each domain has its own next-check time), the edge
 * reconciled every {@code DOMAINS_EDGE_INTERVAL} (1 min). Not under {@code test} (tests call {@link CustomDomainJobs}).
 * Replicas share the checks ({@code FOR UPDATE SKIP LOCKED}); one replica at a time reconciles (advisory lock).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
@RequiredArgsConstructor
class CustomDomainScheduler {

    private final CustomDomainJobs jobs;

    @Scheduled(fixedDelayString = "${northline.domains.check-interval:PT1M}", initialDelayString = "PT50S")
    void check() {
        step("DNS checks", jobs::checkDue);
    }

    @Scheduled(fixedDelayString = "${northline.domains.edge-interval:PT1M}", initialDelayString = "PT55S")
    void edge() {
        step("edge", jobs::reconcileEdge);
    }

    private static void step(String name, IntSupplier job) {
        try {
            var changed = job.getAsInt();
            if (changed > 0) {
                log.info("Custom domains '{}': {} domain(s) changed state", name, changed);
            }
        } catch (RuntimeException e) {
            log.error("Custom domains '{}' failed; retrying next run", name, e);
        }
    }
}
