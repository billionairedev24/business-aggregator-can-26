package ca.northline.availability.application;

import ca.northline.availability.domain.BookingRules;
import ca.northline.availability.domain.WeeklyHours;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Outbound port: weekly hours, booking rules and opened holidays of a business. */
public interface HoursRepository {

    /** Per member, the most recently scheduled hours (the highest effective date). */
    List<SavedHours> latest(String merchantId);

    /** The hours in effect for a member on a day. */
    Optional<WeeklyHours> effectiveOn(String merchantId, String memberUserId, LocalDate day);

    /** Replaces the member's hours from {@code effectiveFrom} on (later schedules are dropped). */
    void replace(String merchantId, String memberUserId, LocalDate effectiveFrom, WeeklyHours hours, Instant at);

    Optional<Instant> lastSaved(String merchantId);

    Optional<BookingRules> rules(String merchantId);

    void saveRules(String merchantId, BookingRules rules, Instant at);

    Set<LocalDate> openHolidays(String merchantId);

    void setHolidayOpen(String merchantId, LocalDate date, boolean open);

    record SavedHours(String memberUserId, LocalDate effectiveFrom, WeeklyHours hours) {}
}
