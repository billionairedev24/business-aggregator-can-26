package ca.northline.restricted.domain;

import java.time.LocalDate;
import java.time.Period;

/**
 * What is kept of a verified age: "over N on a date". N is the age in whole years on the day, capped at the strictest
 * minimum age any province asks, so an adult's exact age (and with it their birth year) is not kept.
 */
public final class AgeRecord {

    private AgeRecord() {}

    /** The age to keep: {@code age} capped at {@code highestMinimumAge} (never below 0). */
    public static int capped(int age, int highestMinimumAge) {
        return Math.max(0, Math.min(age, Math.max(highestMinimumAge, 0)));
    }

    /** What the person is at least on {@code today}: the kept age plus the whole years since it was verified. */
    public static int floor(int overAge, LocalDate verifiedOn, LocalDate today) {
        if (today.isBefore(verifiedOn)) {
            return overAge;
        }
        return overAge + Period.between(verifiedOn, today).getYears();
    }
}
