package ca.northline.availability.application;

import static ca.northline.availability.application.Team.ZONE;

import ca.northline.availability.api.AvailabilityChanged;
import ca.northline.availability.application.AvailabilityUseCases.AddTimeOff;
import ca.northline.availability.application.AvailabilityUseCases.CountConflicts;
import ca.northline.availability.application.AvailabilityUseCases.HolidayView;
import ca.northline.availability.application.AvailabilityUseCases.Member;
import ca.northline.availability.application.AvailabilityUseCases.RemoveTimeOff;
import ca.northline.availability.application.AvailabilityUseCases.SetHolidayOpen;
import ca.northline.availability.application.AvailabilityUseCases.TimeOffEntry;
import ca.northline.availability.application.AvailabilityUseCases.TimeOffView;
import ca.northline.availability.application.AvailabilityUseCases.ViewTimeOff;
import ca.northline.availability.domain.AlbertaHolidays;
import ca.northline.availability.domain.BookingRules;
import ca.northline.availability.domain.TimeOff;
import ca.northline.booking.api.BookingCalendar;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Time off, special hours and Alberta statutory holidays. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class TimeOffService implements ViewTimeOff, AddTimeOff, RemoveTimeOff, CountConflicts, SetHolidayOpen {

    static final String NOT_A_MEMBER = "This person isn't on your team.";
    static final String NOT_A_HOLIDAY = "This day is not a statutory holiday in Alberta.";
    static final int HOLIDAYS_SHOWN = 5;

    private final TimeOffRepository timeOff;
    private final HoursRepository hours;
    private final Team team;
    private final BookingCalendar calendar;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    public TimeOffView view(String merchantId) {
        var today = LocalDate.now(clock.withZone(ZONE));
        var names = names(merchantId);
        var open = hours.openHolidays(merchantId);
        var holidays = AlbertaHolidays.upcoming(today, HOLIDAYS_SHOWN).stream()
                .map(h -> new HolidayView(h, open.contains(h.date())))
                .toList();
        var entries = timeOff.from(merchantId, today).stream()
                .map(t -> new TimeOffEntry(t, name(names, t.memberUserId())))
                .toList();
        long premium = hours.rules(merchantId).orElseGet(BookingRules::defaults).holidayPremiumCents();
        return new TimeOffView(entries, holidays, premium);
    }

    @Override
    @Transactional
    public TimeOffEntry add(AddTimeOff.Command c) {
        var names = names(c.merchantId());
        if (c.memberUserId() != null && !names.containsKey(c.memberUserId())) {
            throw RuleViolation.of("memberUserId", "not_found", NOT_A_MEMBER);
        }
        var entry = TimeOff.create(c.memberUserId(), c.startsOn(), c.endsOn(), c.kind(), c.specialRanges(), c.reason());
        var at = clock.instant();
        timeOff.insert(c.merchantId(), entry, c.actorId(), at);
        events.publishEvent(new AvailabilityChanged(Ids.next(), at, c.merchantId(), c.actorId(), "time_off"));
        return new TimeOffEntry(entry, name(names, entry.memberUserId()));
    }

    @Override
    @Transactional
    public void remove(String merchantId, String actorId, String timeOffId) {
        if (!timeOff.delete(merchantId, timeOffId)) {
            throw new NotFound("time off", timeOffId);
        }
        events.publishEvent(new AvailabilityChanged(Ids.next(), clock.instant(), merchantId, actorId, "time_off"));
    }

    @Override
    public int count(String merchantId, @Nullable String memberUserId, LocalDate from, LocalDate to) {
        var last = to.isBefore(from) ? from : to;
        return calendar.busy(
                        merchantId,
                        memberUserId,
                        from.atStartOfDay(ZONE).toInstant(),
                        last.plusDays(1).atStartOfDay(ZONE).toInstant())
                .size();
    }

    @Override
    @Transactional
    public HolidayView set(String merchantId, String actorId, LocalDate date, boolean open) {
        var holiday = AlbertaHolidays.of(date.getYear()).stream()
                .filter(h -> h.date().equals(date))
                .findFirst()
                .orElseThrow(() -> RuleViolation.of("date", "holiday", NOT_A_HOLIDAY));
        hours.setHolidayOpen(merchantId, date, open);
        events.publishEvent(new AvailabilityChanged(Ids.next(), clock.instant(), merchantId, actorId, "holidays"));
        return new HolidayView(holiday, open);
    }

    private Map<String, Member> names(String merchantId) {
        return team.members(merchantId).stream().collect(Collectors.toMap(Member::userId, Function.identity()));
    }

    private static @Nullable String name(Map<String, Member> names, @Nullable String userId) {
        if (userId == null) {
            return null;
        }
        var m = names.get(userId);
        return m == null ? null : m.name();
    }
}
