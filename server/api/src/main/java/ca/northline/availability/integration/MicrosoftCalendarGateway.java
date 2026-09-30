package ca.northline.availability.integration;

import static ca.northline.availability.integration.CalendarHttp.authorized;
import static ca.northline.availability.integration.CalendarHttp.authorizedRun;
import static ca.northline.availability.integration.CalendarHttp.bearer;
import static ca.northline.availability.integration.CalendarHttp.form;
import static ca.northline.availability.integration.CalendarHttp.text;

import ca.northline.availability.application.CalendarGateway;
import ca.northline.availability.domain.CalendarProvider;
import ca.northline.availability.domain.CalendarScope;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PatchExchange;
import org.springframework.web.service.annotation.PostExchange;
import tools.jackson.databind.JsonNode;

/**
 * Microsoft Graph v1.0 with the Microsoft identity platform (v2.0 endpoints, authorization code + PKCE, tenant {@code
 * common} by default: work, school and personal Microsoft accounts). One delegated permission, {@code
 * Calendars.ReadWrite} (read busy times, list calendars, write bookings), plus {@code openid profile offline_access}.
 * Busy times come from {@code calendarView/delta} (recurrences expanded by Graph, times in UTC through {@code Prefer:
 * outlook.timezone}); change notifications are Graph subscriptions with {@code clientState}. Microsoft has no endpoint
 * an app can call to revoke one user's consent, so {@link #revoke} does nothing (the service destroys the token).
 * Written from Microsoft's documentation and tested against WireMock; it has never run against Graph.
 */
@Slf4j
class MicrosoftCalendarGateway implements CalendarGateway {

    static final String CALENDARS = "https://graph.microsoft.com/Calendars.ReadWrite";
    static final String SCOPES = "openid profile offline_access " + CALENDARS;
    static final List<String> PREFER = List.of("odata.maxpagesize=200", "outlook.timezone=\"UTC\"");
    static final String TRANSACTION_PREFIX = "nl-";
    static final ZoneId ALL_DAY_ZONE = ZoneId.of("America/Edmonton");

    interface LoginApi {
        @PostExchange(url = "/{tenant}/oauth2/v2.0/token", contentType = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
        JsonNode token(@PathVariable String tenant, @RequestBody MultiValueMap<String, String> form);
    }

    interface Graph {
        @GetExchange("/me/calendars")
        JsonNode calendars(@RequestHeader("Authorization") String auth);

        @GetExchange("/me/calendar")
        JsonNode defaultCalendar(@RequestHeader("Authorization") String auth);

        @GetExchange("/me/calendars/{calendarId}/calendarView/delta")
        JsonNode delta(
                @RequestHeader("Authorization") String auth,
                @RequestHeader("Prefer") List<String> prefer,
                @PathVariable String calendarId,
                @RequestParam String startDateTime,
                @RequestParam String endDateTime);

        /** {@code @odata.nextLink} / {@code @odata.deltaLink}: absolute URLs Graph hands out. */
        @GetExchange
        JsonNode follow(
                URI link, @RequestHeader("Authorization") String auth, @RequestHeader("Prefer") List<String> prefer);

        @PostExchange("/subscriptions")
        JsonNode subscribe(@RequestHeader("Authorization") String auth, @RequestBody Map<String, Object> body);

        @PatchExchange("/subscriptions/{id}")
        JsonNode renew(
                @RequestHeader("Authorization") String auth,
                @PathVariable String id,
                @RequestBody Map<String, Object> body);

        @DeleteExchange("/subscriptions/{id}")
        void unsubscribe(@RequestHeader("Authorization") String auth, @PathVariable String id);

        @PostExchange("/me/calendars/{calendarId}/events")
        JsonNode create(
                @RequestHeader("Authorization") String auth,
                @PathVariable String calendarId,
                @RequestBody Map<String, Object> body);

        @PatchExchange("/me/events/{eventId}")
        JsonNode update(
                @RequestHeader("Authorization") String auth,
                @PathVariable String eventId,
                @RequestBody Map<String, Object> body);

        @DeleteExchange("/me/events/{eventId}")
        void delete(@RequestHeader("Authorization") String auth, @PathVariable String eventId);
    }

    private final CalendarProperties.Microsoft config;
    private final LoginApi login;
    private final Graph graph;
    private final Clock clock;

    MicrosoftCalendarGateway(CalendarProperties.Microsoft config, LoginApi login, Graph graph, Clock clock) {
        this.config = config;
        this.login = login;
        this.graph = graph;
        this.clock = clock;
    }

    @Override
    public CalendarProvider provider() {
        return CalendarProvider.OUTLOOK;
    }

    @Override
    public boolean available() {
        return config.configured();
    }

    // ── OAuth ────────────────────────────────────────────────────────────────────

    @Override
    public URI authorizationUrl(Authorization request) {
        var p = CalendarHttp.params();
        p.put("client_id", config.id());
        p.put("response_type", "code");
        p.put("redirect_uri", request.redirectUri().toString());
        p.put("response_mode", "query");
        p.put("scope", SCOPES);
        p.put("state", request.state());
        p.put("code_challenge", request.codeChallenge());
        p.put("code_challenge_method", "S256");
        if (request.firstConsent()) {
            p.put("prompt", "select_account");
        }
        return URI.create(config.loginUrl() + "/" + config.tenant() + "/oauth2/v2.0/authorize" + CalendarHttp.query(p));
    }

    @Override
    public Grant exchange(String code, String codeVerifier, URI redirectUri) {
        var token = CalendarHttp.tokenCall(() -> login.token(
                config.tenant(),
                form(
                        "grant_type",
                        "authorization_code",
                        "code",
                        code,
                        "code_verifier",
                        codeVerifier,
                        "redirect_uri",
                        redirectUri.toString(),
                        "scope",
                        SCOPES,
                        "client_id",
                        config.id(),
                        "client_secret",
                        config.secret())));
        var claims = CalendarHttp.idTokenClaims(text(token, "id_token"));
        var oid = text(claims, "oid");
        var subject = oid != null ? oid : text(claims, "sub");
        if (subject == null) {
            throw new IllegalStateException("Microsoft returned no id_token oid/sub");
        }
        var username = text(claims, "preferred_username");
        var label = username != null ? username : text(claims, "email");
        return new Grant(
                required(token, "access_token"),
                text(token, "refresh_token"),
                CalendarHttp.expiresAt(token, clock.instant()),
                scopes(text(token, "scope")),
                subject,
                label == null ? "Microsoft account" : label);
    }

    @Override
    public Token refresh(String refreshToken) {
        var token = CalendarHttp.tokenCall(() -> login.token(
                config.tenant(),
                form(
                        "grant_type",
                        "refresh_token",
                        "refresh_token",
                        refreshToken,
                        "scope",
                        SCOPES,
                        "client_id",
                        config.id(),
                        "client_secret",
                        config.secret())));
        return new Token(
                required(token, "access_token"),
                text(token, "refresh_token"),
                CalendarHttp.expiresAt(token, clock.instant()));
    }

    /** No per-user revocation endpoint exists for an app (see the class comment). */
    @Override
    public void revoke(String refreshToken) {
        log.debug("Microsoft grants can't be revoked by the app; the stored token is destroyed instead");
    }

    /** Graph answers with the short form ("Calendars.ReadWrite …") or the full resource URL. */
    static Set<CalendarScope> scopes(@Nullable String granted) {
        var scopes = EnumSet.noneOf(CalendarScope.class);
        if (granted != null && granted.toLowerCase(Locale.ROOT).contains("calendars.readwrite")) {
            scopes.add(CalendarScope.EVENTS);
            scopes.add(CalendarScope.CALENDAR_LIST);
        }
        return scopes;
    }

    // ── reading ──────────────────────────────────────────────────────────────────

    @Override
    public List<RemoteCalendar> calendars(String accessToken) {
        var body = authorized(() -> graph.calendars(bearer(accessToken)));
        var calendars = new ArrayList<RemoteCalendar>();
        for (var item : body.path("value")) {
            calendars.add(new RemoteCalendar(
                    required(item, "id"),
                    Objects.requireNonNullElse(text(item, "name"), "Calendar"),
                    item.path("isDefaultCalendar").asBoolean(false)));
        }
        return calendars;
    }

    @Override
    public RemoteCalendar writeCalendar(String accessToken) {
        var item = authorized(() -> graph.defaultCalendar(bearer(accessToken)));
        return new RemoteCalendar(
                required(item, "id"), Objects.requireNonNullElse(text(item, "name"), "Calendar"), true);
    }

    @Override
    public Changes changes(String accessToken, String calendarId, @Nullable String cursor, Instant from, Instant to) {
        var busy = new ArrayList<BusyEvent>();
        var removed = new ArrayList<String>();
        JsonNode page;
        try {
            page = cursor == null
                    ? authorized(() -> graph.delta(
                            bearer(accessToken),
                            PREFER,
                            calendarId,
                            from.truncatedTo(ChronoUnit.SECONDS).toString(),
                            to.truncatedTo(ChronoUnit.SECONDS).toString()))
                    : authorized(() -> graph.follow(URI.create(cursor), bearer(accessToken), PREFER));
            while (true) {
                for (var item : page.path("value")) {
                    classify(item, busy, removed);
                }
                var next = text(page, "@odata.nextLink");
                if (next == null) {
                    break;
                }
                page = authorized(() -> graph.follow(URI.create(next), bearer(accessToken), PREFER));
            }
        } catch (HttpClientErrorException e) {
            var code = CalendarHttp.errorCode(e);
            if (e.getStatusCode().value() == 410
                    || "syncStateNotFound".equalsIgnoreCase(code)
                    || "resyncRequired".equalsIgnoreCase(code)
                    || "syncStateInvalid".equalsIgnoreCase(code)) {
                throw new CursorExpired("Graph delta link expired: " + code);
            }
            throw e;
        }
        var delta = text(page, "@odata.deltaLink");
        if (delta == null) {
            throw new IllegalStateException("Graph returned no deltaLink");
        }
        return new Changes(busy, removed, delta, cursor == null);
    }

    static void classify(JsonNode item, List<BusyEvent> busy, List<String> removed) {
        var id = text(item, "id");
        if (id == null) {
            return;
        }
        var gone = item.has("@removed") || item.path("isCancelled").asBoolean(false);
        var showAs = Objects.requireNonNullElse(text(item, "showAs"), "busy");
        var free = showAs.equals("free") || showAs.equals("workingElsewhere");
        var declined = "declined".equals(text(item.path("responseStatus"), "response"));
        var transaction = text(item, "transactionId");
        var ours = transaction != null && transaction.startsWith(TRANSACTION_PREFIX);
        var allDay = item.path("isAllDay").asBoolean(false);
        var start = time(item.path("start"), allDay);
        var end = time(item.path("end"), allDay);
        if (gone || free || declined || ours || start == null || end == null || !end.isAfter(start)) {
            removed.add(id);
        } else {
            busy.add(new BusyEvent(id, start, end));
        }
    }

    /** Graph date-times have no offset ({@code 2026-10-01T15:00:00.0000000}) and are in UTC (Prefer header). */
    static @Nullable Instant time(JsonNode node, boolean allDay) {
        var dateTime = text(node, "dateTime");
        if (dateTime == null) {
            return null;
        }
        if (dateTime.endsWith("Z") || dateTime.matches(".*[+-]\\d\\d:\\d\\d$")) {
            return OffsetDateTime.parse(dateTime).toInstant();
        }
        var local = LocalDateTime.parse(dateTime);
        return allDay ? local.toLocalDate().atStartOfDay(ALL_DAY_ZONE).toInstant() : local.toInstant(ZoneOffset.UTC);
    }

    // ── notifications ────────────────────────────────────────────────────────────

    @Override
    public Subscription watch(Watch request) {
        var body = new LinkedHashMap<String, Object>();
        body.put("changeType", "created,updated,deleted");
        body.put("notificationUrl", request.address().toString());
        body.put("lifecycleNotificationUrl", request.address() + "/lifecycle");
        body.put("resource", "me/calendars/" + request.calendarId() + "/events");
        body.put(
                "expirationDateTime",
                request.until().truncatedTo(ChronoUnit.SECONDS).toString());
        body.put("clientState", request.secret());
        var answer = authorized(() -> graph.subscribe(bearer(request.accessToken()), body));
        return new Subscription(required(answer, "id"), expiry(answer, request.until()));
    }

    @Override
    public Optional<Subscription> renew(String accessToken, String channelId, String externalId, Instant until) {
        try {
            var answer = authorized(() -> graph.renew(
                    bearer(accessToken),
                    externalId,
                    Map.of(
                            "expirationDateTime",
                            until.truncatedTo(ChronoUnit.SECONDS).toString())));
            return Optional.of(new Subscription(externalId, expiry(answer, until)));
        } catch (HttpClientErrorException.NotFound _) {
            return Optional.empty();
        }
    }

    @Override
    public void unwatch(String accessToken, String channelId, String externalId) {
        try {
            authorizedRun(() -> graph.unsubscribe(bearer(accessToken), externalId));
        } catch (HttpClientErrorException.NotFound _) {
            // already gone
        }
    }

    // ── writing ──────────────────────────────────────────────────────────────────

    /** {@code transactionId} = the booking: Graph drops a retried create instead of making a second event. */
    @Override
    public String create(String accessToken, String calendarId, BookingEvent event) {
        var body = body(event);
        body.put("transactionId", TRANSACTION_PREFIX + event.bookingId());
        return required(authorized(() -> graph.create(bearer(accessToken), calendarId, body)), "id");
    }

    @Override
    public void update(String accessToken, String calendarId, String eventId, BookingEvent event) {
        try {
            authorized(() -> graph.update(bearer(accessToken), eventId, body(event)));
        } catch (HttpClientErrorException.NotFound e) {
            throw new EventGone("Graph event " + eventId + " not found");
        }
    }

    @Override
    public void delete(String accessToken, String calendarId, String eventId) {
        try {
            authorizedRun(() -> graph.delete(bearer(accessToken), eventId));
        } catch (HttpClientErrorException.NotFound _) {
            // already gone
        }
    }

    static Map<String, Object> body(BookingEvent event) {
        var body = new LinkedHashMap<String, Object>();
        body.put("subject", event.summary());
        body.put("body", Map.of("contentType", "text", "content", event.description()));
        body.put("start", Map.of("dateTime", utc(event.startsAt()), "timeZone", "UTC"));
        body.put("end", Map.of("dateTime", utc(event.endsAt()), "timeZone", "UTC"));
        if (event.location() != null) {
            body.put("location", Map.of("displayName", event.location()));
        }
        body.put("showAs", "busy");
        return body;
    }

    private static String utc(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.SECONDS), ZoneOffset.UTC)
                .toString();
    }

    private static Instant expiry(JsonNode answer, Instant fallback) {
        var value = text(answer, "expirationDateTime");
        return value == null ? fallback : OffsetDateTime.parse(value).toInstant();
    }

    private static String required(JsonNode node, String field) {
        var value = text(node, field);
        if (value == null) {
            throw new IllegalStateException("Graph answer without " + field);
        }
        return value;
    }
}
