package ca.northline.availability.application;

import ca.northline.availability.domain.CalendarLinkState;
import ca.northline.availability.domain.CalendarProvider;
import ca.northline.availability.domain.CalendarScope;
import ca.northline.shared.crypto.SecretSealer.Sealed;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Outbound port for {@code availability.calendar_links}. */
public interface CalendarLinkRepository {

    List<Link> of(String merchantId, String memberUserId);

    Optional<Link> find(String merchantId, String memberUserId, CalendarProvider provider);

    Optional<Link> byId(String linkId);

    /** Locks the link for a write-back ({@code FOR NO KEY UPDATE SKIP LOCKED}); empty when another replica holds it. */
    Optional<Link> lock(String linkId);

    /**
     * S-136: locks the link ({@code FOR NO KEY UPDATE}), waiting for a transaction that holds it; empty when it is gone.
     * Lock order: a transaction that locks or changes a link and the rows under it (sources, busy blocks, channels,
     * mirrors) locks the link first, in the strongest mode it will need, so it never upgrades later.
     */
    Optional<Link> lockWaiting(String linkId);

    /** S-136: {@code FOR UPDATE} (disconnect deletes the link and, by cascade, everything under it); waits. */
    Optional<Link> lockForDelete(String linkId);

    /** Google and Outlook links that still work (the jobs' work list). */
    List<Link> connected();

    /** iCal: insert or replace the member's link for the provider. */
    void upsert(String merchantId, Link link);

    /**
     * Google / Outlook after a successful authorization: inserts the link, or updates the member's existing one (same id)
     * with the new account, scopes and sealed refresh token, back in state {@code connected}.
     */
    Link saveGrant(Link link, Sealed refreshToken);

    /** The sealed refresh token (context = the link id). */
    Optional<Sealed> refreshToken(String linkId);

    /** Microsoft rotates refresh tokens: keep the newest. */
    void replaceRefreshToken(String linkId, Sealed refreshToken);

    void markReconnect(String linkId, String error, Instant at);

    void synced(String linkId, Instant at);

    void delete(String merchantId, String memberUserId, CalendarProvider provider);

    /**
     * @param tokenRef iCal: the feed token; Google / Outlook: the key that sealed the refresh token
     * @param writeCalendarId where bookings are written (Google {@code primary}, the Outlook default calendar)
     */
    record Link(
            String id,
            String merchantId,
            String memberUserId,
            CalendarProvider provider,
            String accountLabel,
            String tokenRef,
            Instant connectedAt,
            @Nullable Instant lastSyncAt,
            CalendarLinkState state,
            Set<CalendarScope> scopes,
            @Nullable String externalAccountId,
            @Nullable String writeCalendarId) {
        public Link {
            scopes = Set.copyOf(scopes);
        }

        public static Link ical(String id, String merchantId, String memberUserId, String feedToken, Instant at) {
            return new Link(
                    id,
                    merchantId,
                    memberUserId,
                    CalendarProvider.ICAL,
                    "Read-only feed",
                    feedToken,
                    at,
                    null,
                    CalendarLinkState.CONNECTED,
                    Set.of(),
                    null,
                    null);
        }

        public boolean needsReconnect() {
            return state == CalendarLinkState.RECONNECT;
        }
    }
}
