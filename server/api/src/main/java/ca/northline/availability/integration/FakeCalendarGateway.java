package ca.northline.availability.integration;

import ca.northline.availability.application.CalendarGateway;
import ca.northline.availability.application.CalendarSettings;
import ca.northline.availability.domain.CalendarProvider;
import ca.northline.availability.domain.CalendarScope;
import ca.northline.shared.Ids;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * {@code northline.calendar.provider=local} (the {@code local} and {@code test} default; refused under staging/prod):
 * Google and Outlook without accounts. "Connect" goes straight back to the callback with a fake code, the member has a
 * "Work" and a "Family" calendar, the first read of the primary one has a lunch block tomorrow 12:00–13:00 (the business's zone) so
 * the preview shows a blocked slot, and bookings written back are kept in memory and logged. Refresh tokens are
 * sealed like real ones; {@code fake-refresh-revoked} is refused, to try the "Reconnect" state.
 */
@Slf4j
class FakeCalendarGateway implements CalendarGateway {

    static final String REVOKED = "fake-refresh-revoked";

    private final CalendarProvider provider;
    private final CalendarSettings settings;
    private final Clock clock;
    private final Map<String, BookingEvent> written = new ConcurrentHashMap<>();

    FakeCalendarGateway(CalendarProvider provider, CalendarSettings settings, Clock clock) {
        this.provider = provider;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public CalendarProvider provider() {
        return provider;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public URI authorizationUrl(Authorization request) {
        var p = CalendarHttp.params();
        p.put("code", "fake-" + Ids.next().toLowerCase(Locale.ROOT));
        p.put("state", request.state());
        return URI.create(settings.redirectUri(provider.code()) + CalendarHttp.query(p));
    }

    @Override
    public Grant exchange(String code, String codeVerifier, URI redirectUri) {
        if (!code.startsWith("fake-")) {
            throw new GrantRevoked("invalid_grant");
        }
        var now = clock.instant();
        return new Grant(
                "fake-access-" + Ids.next(),
                "fake-refresh-" + Ids.next(),
                now.plus(Duration.ofHours(1)),
                EnumSet.allOf(CalendarScope.class),
                "fake-" + provider.code(),
                provider == CalendarProvider.GOOGLE
                        ? "Google account (local fake)"
                        : "Microsoft 365 account (local fake)");
    }

    @Override
    public Token refresh(String refreshToken) {
        if (REVOKED.equals(refreshToken)) {
            throw new GrantRevoked("invalid_grant");
        }
        return new Token("fake-access-" + Ids.next(), null, clock.instant().plus(Duration.ofHours(1)));
    }

    @Override
    public void revoke(String refreshToken) {
        log.info("Local calendar ({}): grant revoked", provider.code());
    }

    @Override
    public List<RemoteCalendar> calendars(String accessToken) {
        return List.of(writeCalendar(accessToken), new RemoteCalendar("family", "Family (local fake)", false));
    }

    @Override
    public RemoteCalendar writeCalendar(String accessToken) {
        return new RemoteCalendar("primary", "Work (local fake)", true);
    }

    @Override
    public Changes changes(
            String accessToken, String calendarId, @Nullable String cursor, Instant from, Instant to, ZoneId zone) {
        var next = "fake-cursor-" + clock.millis();
        if (cursor != null || !"primary".equals(calendarId)) {
            return new Changes(List.of(), List.of(), next, cursor == null);
        }
        var tomorrow = LocalDate.now(clock.withZone(zone)).plusDays(1);
        var lunch = new BusyEvent(
                "fake-lunch-" + tomorrow,
                tomorrow.atTime(LocalTime.NOON).atZone(zone).toInstant(),
                tomorrow.atTime(13, 0).atZone(zone).toInstant());
        return new Changes(List.of(lunch), List.of(), next, true);
    }

    @Override
    public Subscription watch(Watch request) {
        return new Subscription("fake-" + request.channelId(), request.until());
    }

    @Override
    public Optional<Subscription> renew(String accessToken, String channelId, String externalId, Instant until) {
        return Optional.of(new Subscription(externalId, until));
    }

    @Override
    public void unwatch(String accessToken, String channelId, String externalId) {
        // nothing to stop
    }

    @Override
    public String create(String accessToken, String calendarId, BookingEvent event) {
        var id = "fake-event-" + event.bookingId().toLowerCase(Locale.ROOT);
        written.put(id, event);
        log.info(
                "Local calendar ({}): wrote \"{}\" {}–{}",
                provider.code(),
                event.summary(),
                event.startsAt(),
                event.endsAt());
        return id;
    }

    @Override
    public void update(String accessToken, String calendarId, String eventId, BookingEvent event) {
        if (written.replace(eventId, event) == null) {
            throw new EventGone(eventId);
        }
        log.info("Local calendar ({}): moved \"{}\" to {}", provider.code(), event.summary(), event.startsAt());
    }

    @Override
    public void delete(String accessToken, String calendarId, String eventId) {
        if (written.remove(eventId) != null) {
            log.info("Local calendar ({}): deleted {}", provider.code(), eventId);
        }
    }
}
