package ca.northline.availability.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The customer's view of a business's live calendar (S-53 "next available", S-55 schedule step): start times every
 * booking interval where a job of the given length fits for at least one bookable team member — weekly hours minus
 * time off, closed holidays, confirmed jobs, busy times from connected calendars (S-32) and other customers' slot holds,
 * each widened by the travel buffer — within the booking rules (minimum notice, same-day cut-off, horizon, jobs per
 * day).
 */
public interface ProviderSlots {

    /**
     * @param closed {@code time_off} or {@code holiday} when nobody works that day because of it, otherwise null
     */
    record Day(LocalDate date, List<Slot> slots, @Nullable String closed) {
        public Day {
            slots = List.copyOf(slots);
        }

        public long free() {
            return slots.stream().filter(Slot::free).count();
        }
    }

    record Slot(Instant startsAt, boolean free) {}

    /**
     * @param customerId the customer asking: their own slot hold doesn't take the slot; null for a guest
     */
    List<Day> days(String merchantId, int durationMin, LocalDate from, int days, @Nullable String customerId);

    /** The earliest free start within the horizon. */
    Optional<Instant> next(String merchantId, int durationMin);

    /** A bookable member free for a job of {@code durationMin} at {@code startsAt}, if any (the least busy first). */
    Optional<String> freeMember(String merchantId, Instant startsAt, int durationMin, @Nullable String customerId);
}
