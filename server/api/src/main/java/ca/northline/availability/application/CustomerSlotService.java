package ca.northline.availability.application;

import static ca.northline.availability.application.Team.ZONE;

import ca.northline.availability.api.ProviderSlots;
import ca.northline.availability.application.AvailabilityUseCases.Member;
import ca.northline.availability.domain.BookingRules;
import ca.northline.availability.domain.SlotPlanner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ProviderSlots}: the Studio's slot preview (same {@link DaySchedule}, same {@link SlotPlanner}) applied to every
 * bookable member and merged — a start is free when at least one member can take it — then limited by the booking
 * rules the customer is subject to.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class CustomerSlotService implements ProviderSlots {

    /** How far "next available" looks at most, whatever the horizon (keeps the provider list cheap). */
    static final int NEXT_LOOKAHEAD_DAYS = 14;

    private final HoursRepository hours;
    private final Team team;
    private final DaySchedule schedule;
    private final Clock clock;

    @Override
    public List<Day> days(String merchantId, int durationMin, LocalDate from, int days, @Nullable String customerId) {
        var rules = hours.rules(merchantId).orElseGet(BookingRules::defaults);
        var members = bookable(merchantId);
        var now = clock.instant();
        var result = new ArrayList<Day>();
        for (int i = 0; i < days; i++) {
            result.add(day(merchantId, members, rules, from.plusDays(i), durationMin, now));
        }
        return List.copyOf(result);
    }

    @Override
    public Optional<Instant> next(String merchantId, int durationMin) {
        var rules = hours.rules(merchantId).orElseGet(BookingRules::defaults);
        var members = bookable(merchantId);
        if (members.isEmpty()) {
            return Optional.empty();
        }
        var now = clock.instant();
        var today = LocalDate.now(clock.withZone(ZONE));
        for (int i = 0; i < Math.min(rules.horizonDays(), NEXT_LOOKAHEAD_DAYS); i++) {
            var free = day(merchantId, members, rules, today.plusDays(i), durationMin, now).slots().stream()
                    .filter(Slot::free)
                    .findFirst();
            if (free.isPresent()) {
                return Optional.of(free.get().startsAt());
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<String> freeMember(
            String merchantId, Instant startsAt, int durationMin, @Nullable String customerId) {
        var rules = hours.rules(merchantId).orElseGet(BookingRules::defaults);
        var date = startsAt.atZone(ZONE).toLocalDate();
        var now = clock.instant();
        if (!bookableTime(rules, date, startsAt, now)) {
            return Optional.empty();
        }
        var start = startsAt.atZone(ZONE).toLocalTime();
        record Candidate(String userId, int jobs) {}
        return bookable(merchantId).stream()
                .<Candidate>mapMulti((m, out) -> {
                    var day = schedule.of(merchantId, m.userId(), date, null);
                    var free = day.jobs() < rules.maxJobsPerDay()
                            && SlotPlanner.preview(
                                            day.ranges(),
                                            day.busy(),
                                            durationMin,
                                            rules.intervalMin(),
                                            rules.bufferMin())
                                    .stream()
                                    .anyMatch(s -> s.free() && s.start().equals(start));
                    if (free) {
                        out.accept(new Candidate(m.userId(), day.jobs()));
                    }
                })
                .min(Comparator.comparingInt(Candidate::jobs).thenComparing(Candidate::userId))
                .map(Candidate::userId);
    }

    private Day day(
            String merchantId, List<Member> members, BookingRules rules, LocalDate date, int durationMin, Instant now) {
        var starts = new TreeMap<LocalTime, Boolean>();
        var closedReasons = new ArrayList<@Nullable String>();
        for (var member : members) {
            var day = schedule.of(merchantId, member.userId(), date, null);
            closedReasons.add(day.closed());
            boolean full = day.jobs() >= rules.maxJobsPerDay();
            for (var slot : SlotPlanner.preview(
                    day.ranges(), day.busy(), durationMin, rules.intervalMin(), rules.bufferMin())) {
                starts.merge(slot.start(), slot.free() && !full, Boolean::logicalOr);
            }
        }
        var slots = starts.entrySet().stream()
                .map(e -> {
                    var at = date.atTime(e.getKey()).atZone(ZONE).toInstant();
                    return new Slot(at, e.getValue() && bookableTime(rules, date, at, now));
                })
                .toList();
        String closed = !members.isEmpty() && closedReasons.stream().allMatch(java.util.Objects::nonNull)
                ? closedReasons.getFirst()
                : null;
        return new Day(date, slots, closed);
    }

    /** Minimum notice, "same day by 9 am" and the horizon. */
    static boolean bookableTime(BookingRules rules, LocalDate date, Instant at, Instant now) {
        var today = now.atZone(ZONE).toLocalDate();
        if (date.isBefore(today) || !date.isBefore(today.plusDays(rules.horizonDays()))) {
            return false;
        }
        if (at.isBefore(now.plus(Duration.ofMinutes(rules.minNoticeMin())))) {
            return false;
        }
        var cutoff = rules.sameDayCutoffMin();
        if (cutoff != null && date.equals(today)) {
            var local = now.atZone(ZONE).toLocalTime();
            return local.getHour() * 60 + local.getMinute() < cutoff;
        }
        return true;
    }

    private List<Member> bookable(String merchantId) {
        return team.members(merchantId).stream().filter(Member::bookable).toList();
    }
}
