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
import ca.northline.availability.domain.CalendarScope;
import ca.northline.merchants.api.TeamRoster;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import java.net.URI;
import java.time.Clock;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Calendar sync (iCal feed; Google and Outlook through {@link CalendarConnectionService}) and who customers can book. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class SyncService implements ViewSync, ConnectCalendar, DisconnectCalendar, SetBookable {

    static final String FEED_BASE = "webcal://northline.ca/cal/";

    private final CalendarLinkRepository links;
    private final CalendarSyncRepository sync;
    private final CalendarGateways gateways;
    private final CalendarConnectionService connections;
    private final HoursRepository hours;
    private final Team team;
    private final TeamRoster roster;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    public SyncView view(String merchantId, String userId) {
        var mine = links.of(merchantId, userId).stream().collect(Collectors.toMap(Link::provider, Function.identity()));
        var calendars = Arrays.stream(CalendarProvider.values())
                .map(p -> view(p, mine.get(p), null))
                .toList();
        var saved = hours.latest(merchantId).stream()
                .collect(Collectors.toMap(HoursRepository.SavedHours::memberUserId, HoursRepository.SavedHours::hours));
        var members = team.members(merchantId).stream()
                .map(m -> new TeamMemberView(m, saved.get(m.userId())))
                .toList();
        return new SyncView(calendars, members);
    }

    /**
     * iCal connects at once. Google / Outlook: an existing working link is returned as is; otherwise (new, or
     * {@code reconnect}) the view carries the provider's consent page for the browser.
     */
    @Override
    @Transactional
    public CalendarView connect(String merchantId, String userId, CalendarProvider provider) {
        if (team.member(merchantId, userId).isEmpty()) {
            throw new NotFound("team member", userId);
        }
        var existing = links.find(merchantId, userId, provider);
        if (existing.isPresent() && !existing.get().needsReconnect()) {
            return view(provider, existing.get(), null);
        }
        if (provider.twoWay()) {
            if (!gateways.available(provider)) {
                throw new Conflict("calendar_provider_unavailable", "This calendar can't be connected yet.");
            }
            var scopes = existing.map(Link::scopes)
                    .filter(s -> !s.isEmpty())
                    .map(EnumSet::copyOf)
                    .orElseGet(() -> EnumSet.of(CalendarScope.EVENTS));
            var url = connections.start(merchantId, userId, provider, scopes, true);
            return view(provider, existing.orElse(null), url);
        }
        var link = Link.ical(Ids.next(), merchantId, userId, Ids.next().toLowerCase(Locale.ROOT), clock.instant());
        links.upsert(merchantId, link);
        return view(provider, link, null);
    }

    @Override
    @Transactional
    public CalendarView disconnect(String merchantId, String userId, CalendarProvider provider) {
        links.find(merchantId, userId, provider).ifPresent(link -> {
            if (provider.twoWay()) {
                connections.disconnect(link);
            } else {
                links.delete(merchantId, userId, provider);
            }
        });
        return view(provider, null, null);
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

    private CalendarView view(CalendarProvider provider, @Nullable Link link, @Nullable URI authorizationUrl) {
        var available = !provider.twoWay() || gateways.available(provider);
        if (link == null) {
            return new CalendarView(provider, false, null, null, null, null, available, List.of(), authorizationUrl);
        }
        var sources = provider.twoWay()
                ? sync.sources(link.id()).stream()
                        .map(CalendarSyncRepository.Source::name)
                        .toList()
                : List.<String>of();
        return new CalendarView(
                provider,
                true,
                link.accountLabel(),
                link.lastSyncAt(),
                provider == CalendarProvider.ICAL ? FEED_BASE + link.tokenRef() + ".ics" : null,
                provider.twoWay() ? link.state() : null,
                available,
                sources,
                authorizationUrl);
    }
}
