package ca.northline.food.domain;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * A kitchen's "today" is its own local date: the zone of its market (region model, S-134), which the caller passes
 * (sold-out-today, holidays, menu schedules).
 */
public final class KitchenTime {
    private KitchenTime() {}

    public static LocalDate today(Clock clock, ZoneId zone) {
        return LocalDate.now(clock.withZone(zone));
    }
}
