package ca.northline.availability.application;

import ca.northline.availability.api.AvailabilityChanged;
import ca.northline.availability.application.AvailabilityUseCases.CalendarView;
import ca.northline.availability.application.AvailabilityUseCases.ConnectCalendar;
import ca.northline.availability.application.AvailabilityUseCases.DisconnectCalendar;
import ca.northline.availability.application.AvailabilityUseCases.SetBookable;
import ca.northline.availability.application.AvailabilityUseCases.SyncView;
import ca.northline.availability.application.AvailabilityUseCases.TeamMemberView;
import ca.northline.availability.application.AvailabilityUseCases.ViewSync;
import ca.northline.availability.application.CalendarLinkRepository.Link;
import ca.northline.availability.domain.CalendarProvider;
import ca.northline.merchants.api.TeamRoster;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Calendar sync (behind the {@link CalendarSync} port) and who customers can book. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class SyncService implements ViewSync, ConnectCalendar, DisconnectCalendar, SetBookable {

    static final String FEED_BASE = "webcal://northline.ca/cal/";

    private final CalendarLinkRepository links;
    private final CalendarSync sync;
    private final HoursRepository hours;
    private final Team team;
    private final TeamRoster roster;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    public SyncView view(String merchantId, String userId) {
        var mine = links.of(merchantId, userId).stream().collect(Collectors.toMap(Link::provider, Function.identity()));
        var calendars = Arrays.stream(CalendarProvider.values())
                .map(p -> view(p, mine.get(p)))
                .toList();
        var saved = hours.latest(merchantId).stream()
                .collect(Collectors.toMap(HoursRepository.SavedHours::memberUserId, HoursRepository.SavedHours::hours));
        var members = team.members(merchantId).stream()
                .map(m -> new TeamMemberView(m, saved.get(m.userId())))
                .toList();
        return new SyncView(calendars, members);
    }

    @Override
    @Transactional
    public CalendarView connect(String merchantId, String userId, CalendarProvider provider) {
        if (team.member(merchantId, userId).isEmpty()) {
            throw new NotFound("team member", userId);
        }
        var existing = links.find(merchantId, userId, provider);
        if (existing.isPresent()) {
            return view(provider, existing.get());
        }
        var connection = sync.connect(provider, merchantId, userId);
        var link = new Link(
                Ids.next(), userId, provider, connection.accountLabel(), connection.tokenRef(), clock.instant(), null);
        links.upsert(merchantId, link);
        return view(provider, link);
    }

    @Override
    @Transactional
    public CalendarView disconnect(String merchantId, String userId, CalendarProvider provider) {
        links.find(merchantId, userId, provider).ifPresent(link -> {
            sync.disconnect(provider, link.tokenRef());
            links.delete(merchantId, userId, provider);
        });
        return view(provider, null);
    }

    @Override
    @Transactional
    public TeamMemberView set(String merchantId, String actorId, String memberUserId, boolean bookable) {
        var member = team.member(merchantId, memberUserId).orElseThrow(() -> new NotFound("team member", memberUserId));
        roster.setBookable(merchantId, memberUserId, bookable);
        events.publishEvent(new AvailabilityChanged(Ids.next(), clock.instant(), merchantId, actorId, "team"));
        Map<String, HoursRepository.SavedHours> saved = hours.latest(merchantId).stream()
                .collect(Collectors.toMap(HoursRepository.SavedHours::memberUserId, Function.identity()));
        var h = saved.get(memberUserId);
        return new TeamMemberView(
                new AvailabilityUseCases.Member(member.userId(), member.name(), member.role(), bookable),
                h == null ? null : h.hours());
    }

    private static CalendarView view(CalendarProvider provider, @Nullable Link link) {
        if (link == null) {
            return new CalendarView(provider, false, null, null, null);
        }
        return new CalendarView(
                provider,
                true,
                link.accountLabel(),
                link.lastSyncAt(),
                provider == CalendarProvider.ICAL ? FEED_BASE + link.tokenRef() + ".ics" : null);
    }
}
