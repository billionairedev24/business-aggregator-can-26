package ca.northline.orders.application;

import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every minute: open checkouts past their 30 minutes give their stock back and release the card holds. */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
class CheckoutJobs {

    private final CheckoutUseCases.ExpireCheckouts expire;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${northline.orders.checkout-expiry-interval:PT1M}", initialDelayString = "PT30S")
    void expireCheckouts() {
        var n = expire.expire(clock.instant());
        if (n > 0) {
            log.info("Expired {} abandoned checkout(s)", n);
        }
    }
}
