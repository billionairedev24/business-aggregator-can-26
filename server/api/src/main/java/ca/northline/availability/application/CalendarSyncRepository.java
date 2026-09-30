package ca.northline.availability.application;

import ca.northline.availability.application.CalendarGateway.BusyEvent;
import ca.northline.availability.domain.CalendarProvider;
import ca.northline.availability.domain.CalendarScope;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port for the S-32 sync tables: OAuth requests in flight, chosen calendars (sources) with their cursors, busy
 * blocks, notification channels and their dedupe log, and the events written back.
 */
public interface CalendarSyncRepository {

    // ── OAuth requests ───────────────────────────────────────────────────────────

    void saveRequest(OAuthRequest request);

    /** Deletes and returns the request (single use); empty when unknown or expired. */
    Optional<OAuthRequest> takeRequest(String stateHash, Instant now);

    int purgeRequests(Instant now);

    // ── sources ──────────────────────────────────────────────────────────────────

    List<Source> sources(String linkId);

    /** Keeps the chosen calendars' cursors, adds the new ones, drops the others (with their blocks and channels). */
    void replaceSources(String linkId, Map<String, String> calendarNames);

    /** Locks the source for a sync ({@code FOR UPDATE SKIP LOCKED}); empty when another replica is syncing it. */
    Optional<Source> lock(String linkId, String calendarId);

    /** Sources of connected links not read since {@code before}, oldest first. */
    List<Source> due(Instant before, int limit);

    void saveCursor(
            String linkId, String calendarId, @Nullable String cursor, @Nullable Instant windowFrom, Instant at);

    // ── busy blocks ──────────────────────────────────────────────────────────────

    void replaceBusy(String linkId, String calendarId, Collection<BusyEvent> events);

    void upsertBusy(String linkId, String calendarId, Collection<BusyEvent> events);

    void removeBusy(String linkId, String calendarId, Collection<String> eventIds);

    /** Busy blocks of the member's connected calendars overlapping [from, to). */
    List<BusyTime> busy(String merchantId, String memberUserId, Instant from, Instant to);

    // ── channels ─────────────────────────────────────────────────────────────────

    /** Committed on its own, before the provider is called (Graph validates the URL during the create call). */
    void insertChannel(Channel channel);

    void activateChannel(String channelId, String externalId, Instant expiresAt);

    void deleteChannel(String channelId);

    Optional<Channel> channel(String channelId);

    Optional<Channel> channelByExternalId(CalendarProvider provider, String externalId);

    List<Channel> channels(String linkId);

    List<Channel> expiringBefore(Instant before, int limit);

    /** Whether a Graph subscription is being created right now (the validation handshake is only answered then). */
    boolean pendingChannel(CalendarProvider provider, Instant since);

    // ── notifications ────────────────────────────────────────────────────────────

    /** False when the notification was already received (dedupe). */
    boolean recordNotification(CalendarProvider provider, String dedupeKey, String channelId, Instant at);

    int purgeNotifications(Instant before);

    // ── write-back ───────────────────────────────────────────────────────────────

    Map<String, Mirror> mirrors(String linkId);

    Set<String> mirroredEventIds(String linkId);

    void saveMirror(Mirror mirror);

    void deleteMirror(String linkId, String bookingId);

    // ── types ────────────────────────────────────────────────────────────────────

    record OAuthRequest(
            String stateHash,
            String merchantId,
            String memberUserId,
            CalendarProvider provider,
            String codeVerifier,
            Set<CalendarScope> scopes,
            Instant createdAt,
            Instant expiresAt) {
        public OAuthRequest {
            scopes = Set.copyOf(scopes);
        }
    }

    record Source(
            String linkId,
            String calendarId,
            String name,
            @Nullable String cursor,
            @Nullable Instant windowFrom,
            @Nullable Instant syncedAt) {}

    record BusyTime(Instant startsAt, Instant endsAt) {}

    record Channel(
            String id,
            String linkId,
            String calendarId,
            CalendarProvider provider,
            @Nullable String externalId,
            String secretHash,
            Instant expiresAt,
            Instant createdAt) {}

    record Mirror(
            String linkId,
            String bookingId,
            String calendarId,
            String externalEventId,
            Instant startsAt,
            Instant endsAt,
            String contentHash,
            Instant writtenAt) {}
}
