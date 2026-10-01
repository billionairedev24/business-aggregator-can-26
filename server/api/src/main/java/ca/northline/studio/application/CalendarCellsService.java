package ca.northline.studio.application;

import ca.northline.availability.api.ProviderSlots;
import ca.northline.booking.api.BookingCalendar;
import ca.northline.identity.api.PersonDirectory;
import ca.northline.region.api.MerchantPlaces;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Open slots come from the customers' own booking calendar ({@link ProviderSlots}: weekly hours minus time off, jobs,
 * connected calendars and other customers' slot holds, within the booking rules), so the Studio shows what a customer
 * could book. A run of consecutive free start times is one "Open slot" at its first time. Quote holds come from {@link
 * BookingCalendar#quoteHolds} for the same days in the business's time zone (S-134); the customer shows in the short
 * form ("M. Tran").
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class CalendarCellsService implements CalendarCells {

    private final ProviderSlots slots;
    private final BookingCalendar calendar;
    private final PersonDirectory people;
    private final MerchantPlaces places;

    @Override
    public Cells of(String merchantId, LocalDate from, int days, int durationMin) {
        var open = new ArrayList<OpenSlot>();
        for (var day : slots.days(merchantId, durationMin, from, days, null)) {
            var shown = 0;
            var previousFree = false;
            for (var slot : day.slots()) {
                if (slot.free() && !previousFree && shown < OPEN_SLOTS_PER_DAY) {
                    open.add(new OpenSlot(slot.startsAt()));
                    shown++;
                }
                previousFree = slot.free();
            }
        }
        var zone = places.of(merchantId).zone();
        var holds = calendar.quoteHolds(
                merchantId,
                from.atStartOfDay(zone).toInstant(),
                from.plusDays(days).atStartOfDay(zone).toInstant());
        var names = people.people(holds.stream()
                .map(BookingCalendar.QuoteHold::customerId)
                .filter(Objects::nonNull)
                .distinct()
                .toList());
        return new Cells(
                open,
                holds.stream()
                        .map(h -> {
                            var person = h.customerId() == null ? null : names.get(h.customerId());
                            return new QuoteHold(
                                    h.quoteId(),
                                    h.requestId(),
                                    h.ref(),
                                    person == null ? "" : person.shortName(),
                                    h.startsAt(),
                                    h.durationMin());
                        })
                        .toList());
    }
}
