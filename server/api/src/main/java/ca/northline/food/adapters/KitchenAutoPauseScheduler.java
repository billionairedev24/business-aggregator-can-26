package ca.northline.food.adapters;

import ca.northline.food.application.KitchenUseCases.KitchenAutoPause;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * S-67: orders become late as time passes, so every minute each kitchen with "Auto-pause if late orders ≥ N" on is
 * checked ({@link KitchenAutoPause#checkAll}); ready / handed-off orders check their kitchen at once. Every replica runs
 * it: a transition is a conditional update, published once. Not under {@code test} (tests call the use case).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
@RequiredArgsConstructor
class KitchenAutoPauseScheduler {

    private final KitchenAutoPause autoPause;

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    void checkKitchens() {
        try {
            var changed = autoPause.checkAll();
            if (changed > 0) {
                log.info("Kitchen auto-pause: {} kitchen(s) paused or resumed", changed);
            }
        } catch (RuntimeException e) {
            log.error("Kitchen auto-pause check failed; retrying in a minute", e);
        }
    }
}
