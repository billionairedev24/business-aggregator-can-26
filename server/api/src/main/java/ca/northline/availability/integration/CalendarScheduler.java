package ca.northline.availability.integration;

import ca.northline.availability.application.CalendarUseCases.CalendarJobs;
import java.util.function.IntSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * S-32 safety net: every {@code CALENDAR_SYNC_INTERVAL} (5 min) each chosen calendar is read incrementally and
 * bookings are written back; hourly, notification channels are renewed (or opened) and old dedupe rows purged. Not
 * under {@code test} (tests call {@link CalendarJobs}). Replicas share the work: calendars are claimed with {@code FOR
 * UPDATE SKIP LOCKED}.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
@RequiredArgsConstructor
class CalendarScheduler {

    private final CalendarJobs jobs;

    @Scheduled(fixedDelayString = "${northline.calendar.sync-interval:PT5M}", initialDelayString = "PT45S")
    void sync() {
        step("read calendars", jobs::syncDue);
        step("write bookings", jobs::writeBackDue);
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT2M")
    void channels() {
        step("renew channels", jobs::renewChannels);
        step("purge", jobs::purge);
    }

    private static void step(String name, IntSupplier job) {
        try {
            var changed = job.getAsInt();
            if (changed > 0) {
                log.info("Calendar job '{}' changed {} item(s)", name, changed);
            }
        } catch (RuntimeException e) {
            log.error("Calendar job '{}' failed; retrying next run", name, e);
        }
    }
}
