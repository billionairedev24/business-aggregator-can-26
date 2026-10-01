package ca.northline.availability.application;

import ca.northline.availability.domain.CalendarProvider;
import ca.northline.availability.domain.CalendarScope;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port to one calendar provider (S-32): OAuth 2.0 authorization code + PKCE, busy times with incremental
 * sync, change notifications, and the events Northline writes. Adapters: Google Calendar API, Microsoft Graph, and a
 * fake for the {@code local} and {@code test} profiles ({@code northline.calendar.provider}).
 *
 * <p>Errors the application reacts to are the nested exceptions; anything else (network, 5xx) is a plain runtime
 * exception and the job retries on its next run.
 */
public interface CalendarGateway {

    CalendarProvider provider();

    /** False when the provider's client id/secret are not configured: the Studio shows it as unavailable. */
    boolean available();

    // ── OAuth ────────────────────────────────────────────────────────────────────

    /** The provider's consent page for {@code request}. */
    URI authorizationUrl(Authorization request);

    /** Code → tokens and the account; throws {@link GrantRevoked} for an invalid or used code. */
    Grant exchange(String code, String codeVerifier, URI redirectUri);

    /** A new access token; throws {@link GrantRevoked} ({@code invalid_grant}) when the member revoked access. */
    Token refresh(String refreshToken);

    /** Revokes the grant at the provider where it has an endpoint for it (best effort, never throws). */
    void revoke(String refreshToken);

    // ── reading ──────────────────────────────────────────────────────────────────

    /** The member's calendars Northline may read (needs {@link CalendarScope#CALENDAR_LIST}). */
    List<RemoteCalendar> calendars(String accessToken);

    /** The calendar Northline writes bookings to (Google {@code primary}, Microsoft's default calendar). */
    RemoteCalendar writeCalendar(String accessToken);

    /**
     * Busy events since {@code cursor} (null = a full read of [from, to)). Throws {@link CursorExpired} when the
     * provider no longer knows the cursor (Google 410, Graph {@code syncStateNotFound}): read again from scratch.
     * All-day events cover their dates in {@code zone}, the business's time zone (region model).
     */
    Changes changes(
            String accessToken, String calendarId, @Nullable String cursor, Instant from, Instant to, ZoneId zone);

    // ── change notifications ─────────────────────────────────────────────────────

    /** Google {@code events.watch} / Graph {@code POST /subscriptions}: {@code secret} comes back with every call. */
    Subscription watch(Watch request);

    /** Extends a subscription in place (Graph); empty when the provider can't (Google: open a new channel instead). */
    Optional<Subscription> renew(String accessToken, String channelId, String externalId, Instant until);

    /** Google {@code channels.stop} / Graph {@code DELETE /subscriptions/{id}}; gone is fine. */
    void unwatch(String accessToken, String channelId, String externalId);

    // ── writing ──────────────────────────────────────────────────────────────────

    /** Creates the event (idempotent per booking where the provider allows it) and returns its id. */
    String create(String accessToken, String calendarId, BookingEvent event);

    /** Throws {@link EventGone} when the member deleted the event in their calendar. */
    void update(String accessToken, String calendarId, String eventId, BookingEvent event);

    /** Deleting an event that is already gone is fine. */
    void delete(String accessToken, String calendarId, String eventId);

    // ── types ────────────────────────────────────────────────────────────────────

    record Authorization(
            String state, String codeChallenge, URI redirectUri, Set<CalendarScope> scopes, boolean firstConsent) {
        public Authorization {
            scopes = Set.copyOf(scopes);
        }
    }

    /**
     * @param subject the provider's stable account id (Google {@code sub}, Microsoft {@code oid})
     * @param accountLabel shown in the Studio ("ravi@prairiewrench.ca")
     */
    record Grant(
            String accessToken,
            @Nullable String refreshToken,
            Instant expiresAt,
            Set<CalendarScope> scopes,
            String subject,
            String accountLabel) {
        public Grant {
            scopes = Set.copyOf(scopes);
        }
    }

    /** {@code refreshToken} when the provider rotated it (Microsoft does). */
    record Token(String accessToken, @Nullable String refreshToken, Instant expiresAt) {}

    record RemoteCalendar(String id, String name, boolean primary) {}

    /** Start, end and id — the only things Northline keeps of someone's own events. */
    record BusyEvent(String id, Instant startsAt, Instant endsAt) {}

    /**
     * @param busy events that are (still) busy: insert or move
     * @param removed ids that are gone, cancelled, free, declined or Northline's own: drop
     * @param full true when {@code busy} is the whole calendar (a first read): everything else is dropped
     */
    record Changes(List<BusyEvent> busy, List<String> removed, String cursor, boolean full) {
        public Changes {
            busy = List.copyOf(busy);
            removed = List.copyOf(removed);
        }
    }

    record Watch(String accessToken, String calendarId, String channelId, String secret, URI address, Instant until) {}

    /** @param externalId Google {@code resourceId} / Graph subscription id */
    record Subscription(String externalId, Instant expiresAt) {}

    record BookingEvent(
            String bookingId,
            String summary,
            @Nullable String location,
            String description,
            Instant startsAt,
            Instant endsAt) {}

    /** The grant is gone ({@code invalid_grant}): the link needs a reconnect. */
    final class GrantRevoked extends RuntimeException {
        public GrantRevoked(String message) {
            super(message);
        }
    }

    /** 401 with an access token that should be valid: refresh once, then treat as revoked. */
    final class Unauthorized extends RuntimeException {
        public Unauthorized(String message) {
            super(message);
        }
    }

    final class CursorExpired extends RuntimeException {
        public CursorExpired(String message) {
            super(message);
        }
    }

    final class EventGone extends RuntimeException {
        public EventGone(String message) {
            super(message);
        }
    }
}
