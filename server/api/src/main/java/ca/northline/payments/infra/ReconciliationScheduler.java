package ca.northline.payments.infra;

import ca.northline.payments.application.BusinessTime;
import ca.northline.payments.application.ReconcileStripe;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * S-85: reconciles yesterday (platform zone) nightly, after Stripe settled the day's balance transactions; and the
 * day before again, for postings that arrived late. Not under {@code test} (tests call {@link ReconcileStripe}).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
@RequiredArgsConstructor
class ReconciliationScheduler {

    private final ReconcileStripe reconcile;
    private final BusinessTime time;
    private final Clock clock;

    @Scheduled(cron = "${northline.payments.reconcile-cron:0 41 4 * * *}", zone = "${northline.region.platform-zone}")
    void nightly() {
        var today = LocalDate.now(clock.withZone(time.platform()));
        for (var day : new LocalDate[] {today.minusDays(2), today.minusDays(1)}) {
            try {
                var result = reconcile.run(day, null);
                if (!result.status().equals("matched")) {
                    log.warn(
                            "Stripe reconciliation {}: {} ({} difference(s), variance {} cents)",
                            day,
                            result.status(),
                            result.mismatches(),
                            result.varianceCents());
                }
            } catch (RuntimeException e) {
                log.error("Stripe reconciliation of {} failed; retried tomorrow or run it from the console", day, e);
            }
        }
    }
}
