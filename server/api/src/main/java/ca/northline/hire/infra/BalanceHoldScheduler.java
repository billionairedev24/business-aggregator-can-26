package ca.northline.hire.infra;

import ca.northline.hire.application.BalanceHolds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * S-153: runs {@link BalanceHolds} every {@code northline.booking.balance.interval} ({@code BOOKING_BALANCE_JOB_INTERVAL},
 * default 5 minutes); off under the {@code test} profile (tests call the job directly). Several replicas may run it:
 * each attempt's Stripe idempotency key and the escrow's uniqueness per booking keep a balance from being held twice.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
@RequiredArgsConstructor
class BalanceHoldScheduler {

    private final BalanceHolds balances;

    @Scheduled(fixedDelayString = "${northline.booking.balance.interval:PT5M}", initialDelayString = "PT45S")
    void run() {
        try {
            var held = balances.holdDue();
            if (held > 0) {
                log.info("Held {} deposit-quote balance(s)", held);
            }
        } catch (RuntimeException e) {
            log.error("Balance job failed; retrying next run", e);
        }
    }
}
