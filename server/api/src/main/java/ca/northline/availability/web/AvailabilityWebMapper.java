package ca.northline.availability.web;

import ca.northline.availability.application.AvailabilityUseCases.CalendarView;
import ca.northline.availability.application.AvailabilityUseCases.HolidayView;
import ca.northline.availability.application.AvailabilityUseCases.HoursView;
import ca.northline.availability.application.AvailabilityUseCases.MemberHours;
import ca.northline.availability.application.AvailabilityUseCases.PreviewSlots.Preview;
import ca.northline.availability.application.AvailabilityUseCases.RulesView;
import ca.northline.availability.application.AvailabilityUseCases.SyncView;
import ca.northline.availability.application.AvailabilityUseCases.TeamMemberView;
import ca.northline.availability.application.AvailabilityUseCases.TimeOffEntry;
import ca.northline.availability.application.AvailabilityUseCases.TimeOffView;
import ca.northline.availability.domain.BookingRules;
import ca.northline.availability.domain.SlotPlanner.Slot;
import ca.northline.availability.domain.TimeRange;
import ca.northline.availability.domain.WeeklyHours;
import ca.northline.availability.web.AvailabilityDtos.CalendarResponse;
import ca.northline.availability.web.AvailabilityDtos.HolidayResponse;
import ca.northline.availability.web.AvailabilityDtos.HoursResponse;
import ca.northline.availability.web.AvailabilityDtos.MemberHoursResponse;
import ca.northline.availability.web.AvailabilityDtos.PreviewResponse;
import ca.northline.availability.web.AvailabilityDtos.RulesBody;
import ca.northline.availability.web.AvailabilityDtos.RulesResponse;
import ca.northline.availability.web.AvailabilityDtos.ServiceResponse;
import ca.northline.availability.web.AvailabilityDtos.SlotResponse;
import ca.northline.availability.web.AvailabilityDtos.SyncResponse;
import ca.northline.availability.web.AvailabilityDtos.TeamMemberResponse;
import ca.northline.availability.web.AvailabilityDtos.TimeOffListResponse;
import ca.northline.availability.web.AvailabilityDtos.TimeOffResponse;
import ca.northline.catalogue.api.CatalogueFacts.ServiceDuration;
import ca.northline.shared.RuleViolation;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
interface AvailabilityWebMapper {

    String TIME_FORMAT = "Enter a time like 09:00.";
    String UNKNOWN_DAY = "Days are mon, tue, wed, thu, fri, sat and sun.";

    HoursResponse toResponse(HoursView view);

    @Mapping(target = "userId", source = "member.userId")
    @Mapping(target = "name", source = "member.name")
    @Mapping(target = "role", source = "member.role")
    @Mapping(target = "bookable", source = "member.bookable")
    @Mapping(target = "days", source = "hours")
    MemberHoursResponse toResponse(MemberHours hours);

    PreviewResponse toResponse(Preview preview);

    default SlotResponse toResponse(Slot slot) {
        return new SlotResponse(slot.start().toString(), slot.free());
    }

    @Mapping(target = "id", source = "serviceId")
    ServiceResponse toResponse(ServiceDuration service);

    List<ServiceResponse> toServiceResponses(List<ServiceDuration> services);

    @Mapping(target = "intervalMin", source = "rules.intervalMin")
    @Mapping(target = "bufferMin", source = "rules.bufferMin")
    @Mapping(target = "minNoticeMin", source = "rules.minNoticeMin")
    @Mapping(target = "sameDayCutoffMin", source = "rules.sameDayCutoffMin")
    @Mapping(target = "horizonDays", source = "rules.horizonDays")
    @Mapping(target = "maxJobsPerDay", source = "rules.maxJobsPerDay")
    @Mapping(target = "acceptMode", source = "rules.acceptMode")
    @Mapping(target = "rescheduleFreeMin", source = "rules.rescheduleFreeMin")
    @Mapping(target = "lateCancelFeeCents", source = "rules.lateCancelFeeCents")
    @Mapping(target = "lateCancelFeeBps", source = "rules.lateCancelFeeBps")
    @Mapping(target = "emergencyPremiumCents", source = "rules.emergencyPremiumCents")
    @Mapping(target = "emergencyPremiumBps", source = "rules.emergencyPremiumBps")
    @Mapping(target = "holidayPremiumCents", source = "rules.holidayPremiumCents")
    @Mapping(target = "serviceAreas", source = "rules.serviceAreas")
    RulesResponse toResponse(RulesView view);

    TimeOffListResponse toResponse(TimeOffView view);

    @Mapping(target = "id", source = "timeOff.id")
    @Mapping(target = "memberUserId", source = "timeOff.memberUserId")
    @Mapping(target = "startsOn", source = "timeOff.startsOn")
    @Mapping(target = "endsOn", source = "timeOff.endsOn")
    @Mapping(target = "kind", source = "timeOff.kind")
    @Mapping(target = "specialRanges", source = "timeOff.specialRanges")
    @Mapping(target = "reason", source = "timeOff.reason")
    TimeOffResponse toResponse(TimeOffEntry entry);

    @Mapping(target = "key", source = "holiday.key")
    @Mapping(target = "date", source = "holiday.date")
    HolidayResponse toResponse(HolidayView view);

    CalendarResponse toResponse(CalendarView view);

    SyncResponse toResponse(SyncView view);

    @Mapping(target = "userId", source = "member.userId")
    @Mapping(target = "name", source = "member.name")
    @Mapping(target = "role", source = "member.role")
    @Mapping(target = "bookable", source = "member.bookable")
    @Mapping(target = "days", source = "hours")
    TeamMemberResponse toResponse(TeamMemberView view);

    // ── value conversions ──

    default List<String> sortedAreas(java.util.Set<String> areas) {
        return areas.stream().sorted().toList();
    }

    default @Nullable Map<String, List<List<String>>> days(@Nullable WeeklyHours hours) {
        if (hours == null) {
            return null;
        }
        var out = new LinkedHashMap<String, List<List<String>>>();
        for (var day : DayOfWeek.values()) {
            out.put(WeeklyHours.key(day), ranges(hours.on(day)));
        }
        return out;
    }

    default List<List<String>> ranges(List<TimeRange> ranges) {
        return ranges.stream()
                .map(r -> List.of(r.start().toString(), r.end().toString()))
                .toList();
    }

    /** {@code {"mon": [["07:00","18:00"]]}} → hours; bad input is a 422 on {@code <prefix>.<day>[i]}. */
    default WeeklyHours toHours(Map<String, List<List<String>>> days, String prefix) {
        var map = new EnumMap<DayOfWeek, List<TimeRange>>(DayOfWeek.class);
        for (var entry : days.entrySet()) {
            var day = java.util.Arrays.stream(DayOfWeek.values())
                    .filter(d -> WeeklyHours.key(d).equals(entry.getKey()))
                    .findFirst()
                    .orElseThrow(() -> RuleViolation.of(prefix + "." + entry.getKey(), "format", UNKNOWN_DAY));
            map.put(day, toRanges(entry.getValue(), prefix + "." + entry.getKey()));
        }
        try {
            return new WeeklyHours(map);
        } catch (RuleViolation e) {
            throw new RuleViolation(e.getViolations().stream()
                    .map(v -> new RuleViolation.Violation(
                            prefix + v.field().substring("days".length()), v.rule(), v.message()))
                    .toList());
        }
    }

    default List<TimeRange> toRanges(@Nullable List<List<String>> ranges, String path) {
        var out = new ArrayList<TimeRange>();
        var list = Objects.requireNonNullElse(ranges, List.<List<String>>of());
        for (int i = 0; i < list.size(); i++) {
            var pair = list.get(i);
            try {
                if (pair == null || pair.size() != 2) {
                    throw new DateTimeParseException("pair", "", 0);
                }
                out.add(new TimeRange(LocalTime.parse(pair.get(0)), LocalTime.parse(pair.get(1))));
            } catch (DateTimeParseException | NullPointerException _) {
                throw RuleViolation.of("%s[%d]".formatted(path, i), "format", TIME_FORMAT);
            }
        }
        return out;
    }

    default BookingRules toRules(RulesBody b) {
        return new BookingRules(
                b.intervalMin(),
                b.bufferMin(),
                b.minNoticeMin(),
                b.sameDayCutoffMin(),
                b.horizonDays(),
                b.maxJobsPerDay(),
                b.acceptMode(),
                b.rescheduleFreeMin(),
                b.lateCancelFeeCents(),
                b.lateCancelFeeBps(),
                b.emergencyPremiumCents(),
                b.emergencyPremiumBps(),
                Objects.requireNonNullElse(b.holidayPremiumCents(), 5000L),
                new java.util.HashSet<>(Objects.requireNonNullElse(b.serviceAreas(), List.of())));
    }
}
