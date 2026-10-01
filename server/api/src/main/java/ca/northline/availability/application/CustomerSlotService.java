package ca.northline.availability.application;

import ca.northline.availability.api.ProviderSlots;
import ca.northline.availability.application.AvailabilityUseCases.Member;
import ca.northline.availability.domain.BookingRules;
import ca.northline.availability.domain.SlotPlanner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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
    private final SlotHoldStore holds;
    private final Clock clock;

    @Override
    public List<Day> days(String merchantId, int durationMin, LocalDate from, int days, @Nullable String customerId) {
        var rules = hours.rules(merchantId).orElseGet(BookingRules::defaults);
        var members = bookable(merchantId);
        var zone = team.zone(merchantId);
        var now = clock.instant();
        var result = new ArrayList<Day>();
        for (int i = 0; i < days; i++) {
            result.add(day(merchantId, members, rules, from.plusDays(i), durationMin, now, customerId, zone));
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
        var zone = team.zone(merchantId);
        var today = LocalDate.now(clock.withZone(zone));
        for (int i = 0; i < Math.min(rules.horizonDays(), NEXT_LOOKAHEAD_DAYS); i++) {
            var free = day(merchantId, members, rules, today.plusDays(i), durationMin, now, null, zone).slots().stream()
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
        return freeMembers(merchantId, startsAt, durationMin, customerId).stream()
                .findFirst();
    }

    /** Every bookable member free for the job at {@code startsAt}, the least busy that day first. */
    List<String> freeMembers(String merchantId, Instant startsAt, int durationMin, @Nullable String customerId) {
        var rules = rules(merchantId);
        var zone = team.zone(merchantId);
        var date = startsAt.atZone(zone).toLocalDate();
        var now = clock.instant();
        if (!bookableTime(rules, date, startsAt, now, zone)) {
            return List.of();
        }
        var start = startsAt.atZone(zone).toLocalTime();
        record Candidate(String userId, int jobs) {}
        return bookable(merchantId).stream()
                .<Candidate>mapMulti((m, out) -> {
                    var day = schedule.of(merchantId, m.userId(), date, null);
                    var free = day.jobs() < rules.maxJobsPerDay()
                            && SlotPlanner.preview(
                                            day.ranges(),
                                            busy(day, merchantId, m.userId(), date, now, customerId, zone),
                                            durationMin,
                                            rules.intervalMin(),
                                            rules.bufferMin())
                                    .stream()
                                    .anyMatch(s -> s.free() && s.start().equals(start));
                    if (free) {
                        out.accept(new Candidate(m.userId(), day.jobs()));
                    }
                })
                .sorted(Comparator.comparingInt(Candidate::jobs).thenComparing(Candidate::userId))
                .map(Candidate::userId)
                .toList();
    }

    BookingRules rules(String merchantId) {
        return hours.rules(merchantId).orElseGet(BookingRules::defaults);
    }

    /** Jobs and calendar busy blocks, plus other customers' slot holds (S-55). */
    private List<SlotPlanner.Busy> busy(
            DaySchedule.Day day,
            String merchantId,
            String memberUserId,
            LocalDate date,
            Instant now,
            @Nullable String customerId,
            ZoneId zone) {
        var held = holds
                .ofMember(
                        merchantId,
                        memberUserId,
                        DaySchedule.start(date, zone),
                        DaySchedule.start(date.plusDays(1), zone),
                        now)
                .stream()
                .filter(h -> !h.customerId().equals(customerId))
                .map(h -> DaySchedule.busy(h.startsAt(), h.endsAt(), date, zone))
                .toList();
        if (held.isEmpty()) {
            return day.busy();
        }
        var all = new ArrayList<>(day.busy());
        all.addAll(held);
        return all;
    }

    private Day day(
            String merchantId,
            List<Member> members,
            BookingRules rules,
            LocalDate date,
            int durationMin,
            Instant now,
            @Nullable String customerId,
            ZoneId zone) {
        var starts = new TreeMap<LocalTime, Boolean>();
        var closedReasons = new ArrayList<@Nullable String>();
        for (var member : members) {
            var day = schedule.of(merchantId, member.userId(), date, null);
            closedReasons.add(day.closed());
            boolean full = day.jobs() >= rules.maxJobsPerDay();
            var busy = busy(day, merchantId, member.userId(), date, now, customerId, zone);
            for (var slot :
                    SlotPlanner.preview(day.ranges(), busy, durationMin, rules.intervalMin(), rules.bufferMin())) {
                starts.merge(slot.start(), slot.free() && !full, Boolean::logicalOr);
            }
        }
        var slots = starts.entrySet().stream()
                .map(e -> {
                    var at = date.atTime(e.getKey()).atZone(zone).toInstant();
                    return new Slot(at, e.getValue() && bookableTime(rules, date, at, now, zone));
                })
                .toList();
        String closed = !members.isEmpty() && closedReasons.stream().allMatch(java.util.Objects::nonNull)
                ? closedReasons.getFirst()
                : null;
        return new Day(date, slots, closed);
    }

    /** Minimum notice, "same day by 9 am" (in the business's zone) and the horizon. */
    static boolean bookableTime(BookingRules rules, LocalDate date, Instant at, Instant now, ZoneId zone) {
        var today = now.atZone(zone).toLocalDate();
        if (date.isBefore(today) || !date.isBefore(today.plusDays(rules.horizonDays()))) {
            return false;
        }
        if (at.isBefore(now.plus(Duration.ofMinutes(rules.minNoticeMin())))) {
            return false;
        }
        var cutoff = rules.sameDayCutoffMin();
        if (cutoff != null && date.equals(today)) {
            var local = now.atZone(zone).toLocalTime();
            return local.getHour() * 60 + local.getMinute() < cutoff;
        }
        return true;
    }

    private List<Member> bookable(String merchantId) {
        return team.members(merchantId).stream().filter(Member::bookable).toList();
    }
}
