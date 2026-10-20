package ca.northline.orders.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every minute (S-151): failed deliveries whose customer didn't choose within the window are refunded. Not under
 * {@code test} (tests call {@link AfterSaleUseCases.FailedDeliveries#refundDue}).
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
class AfterSaleJobs {

    private final AfterSaleUseCases.FailedDeliveries failures;

    @Scheduled(fixedDelayString = "${northline.orders.after-sale.job-interval:PT1M}", initialDelayString = "PT40S")
    void refundUndecided() {
        try {
            var n = failures.refundDue();
            if (n > 0) {
                log.info("Refunded {} failed deliver(ies) nobody decided on", n);
            }
        } catch (RuntimeException e) {
            log.warn("Refunding undecided failed deliveries failed; retrying next minute", e);
        }
    }
}
