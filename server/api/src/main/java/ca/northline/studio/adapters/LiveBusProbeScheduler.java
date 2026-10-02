package ca.northline.studio.adapters;

import ca.northline.studio.application.LiveBusProbe;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** S-113: runs {@link LiveBusProbe} (not under {@code test}: tests call it themselves). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
@RequiredArgsConstructor
class LiveBusProbeScheduler {

    private final LiveBusProbe probe;

    @Scheduled(fixedDelayString = "${northline.live.probe-interval:PT30S}", initialDelayString = "PT15S")
    void probe() {
        probe.probe();
    }
}
