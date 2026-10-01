package ca.northline.fulfilment.application;

import ca.northline.fulfilment.application.DispatchUseCases.AssignCouriers;
import ca.northline.fulfilment.application.DispatchUseCases.PlanRuns;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every minute: plan the runs whose time has come and assign couriers ({@code northline.fulfilment.dispatch-interval});
 * every hour: forget drop-off addresses of deliveries that ended more than {@code address-retention} ago. Not under
 * {@code test} (tests call the use cases).
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
class DispatchJobs {

    private final PlanRuns plan;
    private final AssignCouriers assign;
    private final DeliveryStore deliveries;
    private final FulfilmentProperties props;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${northline.fulfilment.dispatch-interval:PT1M}", initialDelayString = "PT45S")
    void dispatch() {
        try {
            var planned = plan.plan(null);
            var assigned = assign.assign(null);
            if (planned + assigned > 0) {
                log.info("Dispatch: {} run(s) planned, {} assigned", planned, assigned);
            }
        } catch (RuntimeException e) {
            log.warn("Dispatch run failed; retrying next minute", e);
        }
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    @Transactional
    void forgetAddresses() {
        var n = deliveries.forgetAddresses(clock.instant().minus(props.addressRetention()));
        if (n > 0) {
            log.info("Cleared {} drop-off address(es) past retention", n);
        }
    }
}
