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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
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
 * Google Calendar API v3 with Google's OAuth 2.0 for web server apps (authorization code + PKCE, {@code access_type=
 * offline}, {@code include_granted_scopes=true}). Scopes: {@code openid email} and {@code calendar.events.owned} on
 * connect (read and write events on calendars the member owns — busy times, notifications and write-back); {@code
 * calendar.calendarlist.readonly} only when the member opens "Choose calendars". Written from Google's documentation
 * and tested against WireMock; it has never run against Google (no account exists yet).
 */
@Slf4j
class GoogleCalendarGateway implements CalendarGateway {

    static final String EVENTS_SCOPE = "https://www.googleapis.com/auth/calendar.events.owned";
    static final String LIST_SCOPE = "https://www.googleapis.com/auth/calendar.calendarlist.readonly";
    static final String BOOKING_PROPERTY = "northlineBookingId";
    static final ZoneId ALL_DAY_ZONE = ZoneId.of("America/Edmonton");

    interface OAuthApi {
        @PostExchange(url = "/token", contentType = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
        JsonNode token(@RequestBody MultiValueMap<String, String> form);

        @PostExchange(url = "/revoke", contentType = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
        void revoke(@RequestBody MultiValueMap<String, String> form);
    }

    interface Api {
        @GetExchange("/users/me/calendarList")
        JsonNode calendarList(
                @RequestHeader("Authorization") String auth, @RequestParam MultiValueMap<String, String> params);

        @GetExchange("/calendars/{calendarId}/events")
        JsonNode events(
                @RequestHeader("Authorization") String auth,
                @PathVariable String calendarId,
                @RequestParam MultiValueMap<String, String> params);

        @PostExchange("/calendars/{calendarId}/events/watch")
        JsonNode watch(
                @RequestHeader("Authorization") String auth,
                @PathVariable String calendarId,
                @RequestBody Map<String, Object> body);

        @PostExchange("/channels/stop")
        void stop(@RequestHeader("Authorization") String auth, @RequestBody Map<String, Object> body);

        @PostExchange("/calendars/{calendarId}/events?sendUpdates=none")
        JsonNode insert(
                @RequestHeader("Authorization") String auth,
                @PathVariable String calendarId,
                @RequestBody Map<String, Object> body);

        @PatchExchange("/calendars/{calendarId}/events/{eventId}?sendUpdates=none")
        JsonNode patch(
                @RequestHeader("Authorization") String auth,
                @PathVariable String calendarId,
                @PathVariable String eventId,
                @RequestBody Map<String, Object> body);

        @DeleteExchange("/calendars/{calendarId}/events/{eventId}?sendUpdates=none")
        void delete(
                @RequestHeader("Authorization") String auth,
                @PathVariable String calendarId,
                @PathVariable String eventId);
    }

    private final CalendarProperties.Google config;
    private final OAuthApi oauth;
    private final Api api;
    private final Clock clock;

    GoogleCalendarGateway(CalendarProperties.Google config, OAuthApi oauth, Api api, Clock clock) {
        this.config = config;
        this.oauth = oauth;
        this.api = api;
        this.clock = clock;
    }

    @Override
    public CalendarProvider provider() {
        return CalendarProvider.GOOGLE;
    }

    @Override
    public boolean available() {
        return config.configured();
    }

    // ── OAuth ────────────────────────────────────────────────────────────────────

    @Override
    public URI authorizationUrl(Authorization request) {
        var scopes = new ArrayList<>(List.of("openid", "email"));
        if (request.scopes().contains(CalendarScope.EVENTS)) {
            scopes.add(EVENTS_SCOPE);
        }
        if (request.scopes().contains(CalendarScope.CALENDAR_LIST)) {
            scopes.add(LIST_SCOPE);
        }
        var p = CalendarHttp.params();
        p.put("client_id", config.id());
        p.put("redirect_uri", request.redirectUri().toString());
        p.put("response_type", "code");
        p.put("scope", String.join(" ", scopes));
        p.put("access_type", "offline");
        p.put("include_granted_scopes", "true");
        // consent every time: Google only returns a refresh token on consent, and incremental consent needs a new one
        p.put("prompt", "consent");
        p.put("state", request.state());
        p.put("code_challenge", request.codeChallenge());
        p.put("code_challenge_method", "S256");
        return URI.create(config.authUrl() + CalendarHttp.query(p));
    }

    @Override
    public Grant exchange(String code, String codeVerifier, URI redirectUri) {
        var token = CalendarHttp.tokenCall(() -> oauth.token(form(
                "grant_type",
                "authorization_code",
                "code",
                code,
                "code_verifier",
                codeVerifier,
                "redirect_uri",
                redirectUri.toString(),
                "client_id",
                config.id(),
                "client_secret",
                config.secret())));
        var claims = CalendarHttp.idTokenClaims(text(token, "id_token"));
        var subject = text(claims, "sub");
        if (subject == null) {
            throw new IllegalStateException("Google returned no id_token subject");
        }
        var email = text(claims, "email");
        return new Grant(
                required(token, "access_token"),
                text(token, "refresh_token"),
                CalendarHttp.expiresAt(token, clock.instant()),
                scopes(text(token, "scope")),
                subject,
                email == null ? "Google account" : email);
    }

    @Override
    public Token refresh(String refreshToken) {
        var token = CalendarHttp.tokenCall(() -> oauth.token(form(
                "grant_type",
                "refresh_token",
                "refresh_token",
                refreshToken,
                "client_id",
                config.id(),
                "client_secret",
                config.secret())));
        return new Token(
                required(token, "access_token"),
                text(token, "refresh_token"),
                CalendarHttp.expiresAt(token, clock.instant()));
    }

    @Override
    public void revoke(String refreshToken) {
        try {
            oauth.revoke(form("token", refreshToken));
        } catch (RuntimeException e) {
            // 400 invalid_token = already revoked; anything else is logged, the token is destroyed with the link anyway
            log.info("Google token revocation: {}", e.getMessage());
        }
    }

    static Set<CalendarScope> scopes(@Nullable String granted) {
        var scopes = EnumSet.noneOf(CalendarScope.class);
        if (granted == null) {
            return scopes;
        }
        var list = List.of(granted.split("\\s+"));
        if (list.contains(EVENTS_SCOPE) || list.contains("https://www.googleapis.com/auth/calendar.events")) {
            scopes.add(CalendarScope.EVENTS);
        }
        if (list.contains(LIST_SCOPE) || list.contains("https://www.googleapis.com/auth/calendar.readonly")) {
            scopes.add(CalendarScope.CALENDAR_LIST);
        }
        return scopes;
    }

    // ── reading ──────────────────────────────────────────────────────────────────

    @Override
    public List<RemoteCalendar> calendars(String accessToken) {
        var calendars = new ArrayList<RemoteCalendar>();
        String page = null;
        do {
            var params = new LinkedMultiValueMap<String, String>();
            params.add("minAccessRole", "owner");
            if (page != null) {
                params.add("pageToken", page);
            }
            var body = authorized(() -> api.calendarList(bearer(accessToken), params));
            for (var item : body.path("items")) {
                var primary = item.path("primary").asBoolean(false);
                var name = text(item, "summaryOverride");
                calendars.add(new RemoteCalendar(
                        primary ? "primary" : required(item, "id"),
                        name != null ? name : java.util.Objects.requireNonNullElse(text(item, "summary"), "Calendar"),
                        primary));
            }
            page = text(body, "nextPageToken");
        } while (page != null);
        return calendars;
    }

    @Override
    public RemoteCalendar writeCalendar(String accessToken) {
        // calendars.get needs a broader scope than events.owned; "primary" always names the member's own calendar
        return new RemoteCalendar("primary", "Google Calendar", true);
    }

    /**
     * {@code events.list} with {@code singleEvents=true} (recurring events expanded to instances). A first read has no
     * time bounds (Google won't give a sync token for a bounded one); the service keeps only the window it needs.
     */
    @Override
    public Changes changes(String accessToken, String calendarId, @Nullable String cursor, Instant from, Instant to) {
        var busy = new ArrayList<BusyEvent>();
        var removed = new ArrayList<String>();
        String page = null;
        String next = null;
        do {
            var params = new LinkedMultiValueMap<String, String>();
            params.add("singleEvents", "true");
            params.add("maxResults", "2500");
            if (cursor != null) {
                params.add("syncToken", cursor);
            }
            if (page != null) {
                params.add("pageToken", page);
            }
            JsonNode body;
            try {
                body = authorized(() -> api.events(bearer(accessToken), calendarId, params));
            } catch (HttpClientErrorException.Gone e) {
                throw new CursorExpired("Google sync token expired (410)");
            }
            for (var item : body.path("items")) {
                classify(item, busy, removed);
            }
            page = text(body, "nextPageToken");
            next = text(body, "nextSyncToken");
        } while (page != null);
        if (next == null) {
            throw new IllegalStateException("Google returned no nextSyncToken");
        }
        return new Changes(busy, removed, next, cursor == null);
    }

    static void classify(JsonNode item, List<BusyEvent> busy, List<String> removed) {
        var id = text(item, "id");
        if (id == null) {
            return;
        }
        var cancelled = "cancelled".equals(text(item, "status"));
        var free = "transparent".equals(text(item, "transparency"));
        var ours = item.path("extendedProperties").path("private").has(BOOKING_PROPERTY);
        var declined = false;
        for (var attendee : item.path("attendees")) {
            if (attendee.path("self").asBoolean(false) && "declined".equals(text(attendee, "responseStatus"))) {
                declined = true;
            }
        }
        var start = time(item.path("start"), false);
        var end = time(item.path("end"), true);
        if (cancelled || free || ours || declined || start == null || end == null || !end.isAfter(start)) {
            removed.add(id);
        } else {
            busy.add(new BusyEvent(id, start, end));
        }
    }

    /** {@code dateTime} (RFC 3339) or, for all-day events, {@code date} at midnight in Calgary. */
    static @Nullable Instant time(JsonNode node, boolean end) {
        var dateTime = text(node, "dateTime");
        if (dateTime != null) {
            return OffsetDateTime.parse(dateTime).toInstant();
        }
        var date = text(node, "date");
        return date == null
                ? null
                : LocalDate.parse(date).atStartOfDay(ALL_DAY_ZONE).toInstant();
    }

    // ── notifications ────────────────────────────────────────────────────────────

    @Override
    public Subscription watch(Watch request) {
        var body = new LinkedHashMap<String, Object>();
        body.put("id", request.channelId());
        body.put("type", "web_hook");
        body.put("address", request.address().toString());
        body.put("token", request.secret());
        body.put("expiration", request.until().toEpochMilli());
        var answer = authorized(() -> api.watch(bearer(request.accessToken()), request.calendarId(), body));
        var expiration = answer.path("expiration");
        var expires = expiration.isMissingNode()
                ? request.until()
                : Instant.ofEpochMilli(Long.parseLong(
                        expiration.asString(Long.toString(request.until().toEpochMilli()))));
        return new Subscription(required(answer, "resourceId"), expires);
    }

    /** Google channels can't be extended: the service opens a new one and stops this one. */
    @Override
    public Optional<Subscription> renew(String accessToken, String channelId, String externalId, Instant until) {
        return Optional.empty();
    }

    @Override
    public void unwatch(String accessToken, String channelId, String externalId) {
        try {
            authorizedRun(() -> api.stop(bearer(accessToken), Map.of("id", channelId, "resourceId", externalId)));
        } catch (HttpClientErrorException.NotFound _) {
            // already gone
        }
    }

    // ── writing ──────────────────────────────────────────────────────────────────

    /**
     * Inserts with an id derived from the booking (base32hex), so a retried insert is answered 409 and becomes an
     * update instead of a second event.
     */
    @Override
    public String create(String accessToken, String calendarId, BookingEvent event) {
        var id = eventId(event.bookingId());
        var body = body(event);
        body.put("id", id);
        try {
            return required(authorized(() -> api.insert(bearer(accessToken), calendarId, body)), "id");
        } catch (HttpClientErrorException.Conflict _) {
            update(accessToken, calendarId, id, event);
            return id;
        }
    }

    @Override
    public void update(String accessToken, String calendarId, String eventId, BookingEvent event) {
        var body = body(event);
        body.put("status", "confirmed"); // brings back an event the member deleted (it stays in the trash as cancelled)
        try {
            authorized(() -> api.patch(bearer(accessToken), calendarId, eventId, body));
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Gone e) {
            throw new EventGone(
                    "Google event " + eventId + ": " + e.getStatusCode().value());
        }
    }

    @Override
    public void delete(String accessToken, String calendarId, String eventId) {
        try {
            authorizedRun(() -> api.delete(bearer(accessToken), calendarId, eventId));
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Gone _) {
            // already gone
        }
    }

    static Map<String, Object> body(BookingEvent event) {
        var body = new LinkedHashMap<String, Object>();
        body.put("summary", event.summary());
        if (event.location() != null) {
            body.put("location", event.location());
        }
        body.put("description", event.description());
        body.put("start", Map.of("dateTime", event.startsAt().toString(), "timeZone", "UTC"));
        body.put("end", Map.of("dateTime", event.endsAt().toString(), "timeZone", "UTC"));
        body.put("transparency", "opaque");
        body.put("extendedProperties", Map.of("private", Map.of(BOOKING_PROPERTY, event.bookingId())));
        return body;
    }

    /** Google event ids: 5–1024 characters of base32hex ({@code a-v0-9}); hex is a subset. */
    static String eventId(String bookingId) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(("northline:" + bookingId).getBytes(StandardCharsets.UTF_8));
            return "nl" + HexFormat.of().formatHex(digest).substring(0, 40);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String required(JsonNode node, String field) {
        var value = text(node, field);
        if (value == null) {
            throw new IllegalStateException("Google answer without " + field);
        }
        return value;
    }
}
