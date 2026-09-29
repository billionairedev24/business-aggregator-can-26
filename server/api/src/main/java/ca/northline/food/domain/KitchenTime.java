package ca.northline.food.domain;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** Kitchens run on Edmonton local time (CLAUDE.md: times displayed in America/Edmonton). */
public final class KitchenTime {
    private KitchenTime() {}

    public static final ZoneId ZONE = ZoneId.of("America/Edmonton");

    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(ZONE));
    }
}
