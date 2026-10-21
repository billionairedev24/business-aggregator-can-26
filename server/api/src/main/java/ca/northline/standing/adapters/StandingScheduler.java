package ca.northline.standing.adapters;

import ca.northline.standing.application.StandingUseCases.EnforceStanding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * S-156: the standing rules nightly ({@code STANDING_ENFORCEMENT_CRON}, default 05:33 in the platform zone — after the
 * quality scores and S-82's enforcement at 05:23; not under {@code test}: tests call {@link EnforceStanding}).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
@RequiredArgsConstructor
class StandingScheduler {

    private final EnforceStanding enforce;

    @Scheduled(cron = "${northline.standing.enforcement-cron:0 33 5 * * *}", zone = "${northline.region.platform-zone}")
    void nightly() {
        try {
            enforce.run();
        } catch (RuntimeException e) {
            log.error("Standing rule enforcement failed; retried tomorrow", e);
        }
    }
}
