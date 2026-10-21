package ca.northline.orders.application;

import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** S-159: group orders nobody finished within a day are closed; their paid shares go back to the cards. */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
class GroupOrderJobs {

    private final GroupOrderService groups;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${northline.orders.group-expiry-interval:PT15M}", initialDelayString = "PT2M")
    void expire() {
        var n = groups.expire(clock.instant());
        if (n > 0) {
            log.info("Closed {} unfinished group order(s)", n);
        }
    }
}
