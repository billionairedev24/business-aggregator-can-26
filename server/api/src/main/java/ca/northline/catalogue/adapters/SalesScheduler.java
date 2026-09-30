package ca.northline.catalogue.adapters;

import ca.northline.catalogue.application.RecountSales;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * S-38: once a night (03:10 Calgary time) every merchant that still shows 30-day sales is recounted, so sales older
 * than 30 days drop out even when nothing new happens. Events keep the figures current in between. Every replica
 * runs it; a recount is derived and idempotent, so running it twice costs a few queries. Not under {@code test}
 * (tests call {@link RecountSales}).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
@RequiredArgsConstructor
class SalesScheduler {

    private final RecountSales sales;

    @Scheduled(cron = "0 10 3 * * *", zone = "America/Edmonton")
    void recountAll() {
        try {
            log.info("30-day sales recounted for {} merchant(s)", sales.recountAll());
        } catch (RuntimeException e) {
            log.error("30-day sales recount failed; retrying tomorrow", e);
        }
    }
}
