package ca.northline.availability.application;

import static ca.northline.availability.application.Team.ZONE;

import ca.northline.availability.api.AvailabilityChanged;
import ca.northline.availability.application.AvailabilityUseCases.HoursView;
import ca.northline.availability.application.AvailabilityUseCases.ListPreviewServices;
import ca.northline.availability.application.AvailabilityUseCases.MemberHours;
import ca.northline.availability.application.AvailabilityUseCases.PreviewSlots;
import ca.northline.availability.application.AvailabilityUseCases.RulesView;
import ca.northline.availability.application.AvailabilityUseCases.SaveHours;
import ca.northline.availability.application.AvailabilityUseCases.SaveRules;
import ca.northline.availability.application.AvailabilityUseCases.ViewHours;
import ca.northline.availability.application.AvailabilityUseCases.ViewRules;
import ca.northline.availability.domain.BookingRules;
import ca.northline.availability.domain.SlotPlanner;
import ca.northline.availability.domain.WeeklyHours;
import ca.northline.catalogue.api.CatalogueFacts;
import ca.northline.catalogue.api.CatalogueFacts.ServiceDuration;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Weekly hours, the live slot preview and booking rules. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class HoursService implements ViewHours, SaveHours, PreviewSlots, ListPreviewServices, ViewRules, SaveRules {

    static final String EFFECTIVE_IN_PAST = "Pick today or a later date.";
    static final String NOT_A_MEMBER = "This person isn't on your team.";
    /** Offered when the catalogue has no service with a duration yet. */
    static final List<Integer> FALLBACK_DURATIONS = List.of(30, 45, 60, 90);

    private final HoursRepository hours;
    private final Team team;
    private final DaySchedule schedule;
    private final CatalogueFacts catalogue;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    public HoursView view(String merchantId) {
        Map<String, HoursRepository.SavedHours> saved = hours.latest(merchantId).stream()
                .collect(Collectors.toMap(HoursRepository.SavedHours::memberUserId, Function.identity()));
        var members = team.members(merchantId).stream()
                .map(m -> {
                    var s = saved.get(m.userId());
                    return new MemberHours(
                            m, s == null ? null : s.effectiveFrom(), s == null ? new WeeklyHours(Map.of()) : s.hours());
                })
                .toList();
        return new HoursView(members, hours.lastSaved(merchantId).orElse(null));
    }

    @Override
    @Transactional
    public HoursView save(SaveHours.Command command) {
        var today = LocalDate.now(clock.withZone(ZONE));
        if (command.effectiveFrom().isBefore(today)) {
            throw RuleViolation.of("effectiveFrom", "range", EFFECTIVE_IN_PAST);
        }
        var members = team.members(command.merchantId());
        for (int i = 0; i < command.members().size(); i++) {
            var id = command.members().get(i).memberUserId();
            if (members.stream().noneMatch(m -> m.userId().equals(id))) {
                throw RuleViolation.of("members[%d].memberUserId".formatted(i), "not_found", NOT_A_MEMBER);
            }
        }
        var at = clock.instant();
        command.members()
                .forEach(m ->
                        hours.replace(command.merchantId(), m.memberUserId(), command.effectiveFrom(), m.hours(), at));
        events.publishEvent(new AvailabilityChanged(Ids.next(), at, command.merchantId(), command.actorId(), "hours"));
        return view(command.merchantId());
    }

    @Override
    public Preview preview(PreviewSlots.Query query) {
        if (team.member(query.merchantId(), query.memberUserId()).isEmpty()) {
            throw new NotFound("team member", query.memberUserId());
        }
        var rules = hours.rules(query.merchantId()).orElseGet(BookingRules::defaults);
        int interval = Objects.requireNonNullElse(query.intervalMin(), rules.intervalMin());
        int buffer = Objects.requireNonNullElse(query.bufferMin(), rules.bufferMin());
        var day = schedule.of(query.merchantId(), query.memberUserId(), query.date(), query.ranges());
        var slots = SlotPlanner.preview(day.ranges(), day.busy(), query.durationMin(), interval, buffer);
        return new Preview(slots, day.jobs(), day.busyBlocks(), interval, buffer, day.closed());
    }

    @Override
    public List<ServiceDuration> list(String merchantId, String lang) {
        var services = catalogue.services(merchantId, lang);
        if (!services.isEmpty()) {
            return services;
        }
        return FALLBACK_DURATIONS.stream()
                .map(d -> new ServiceDuration("duration-" + d, "", d))
                .toList();
    }

    @Override
    public RulesView rules(String merchantId) {
        return new RulesView(
                hours.rules(merchantId).orElseGet(BookingRules::defaults),
                BookingRules.ZONES,
                hours.lastSaved(merchantId).orElse(null));
    }

    @Override
    @Transactional
    public RulesView saveRules(String merchantId, String actorId, BookingRules rules) {
        var at = clock.instant();
        hours.saveRules(merchantId, rules, at);
        events.publishEvent(new AvailabilityChanged(Ids.next(), at, merchantId, actorId, "rules"));
        return rules(merchantId);
    }
}
