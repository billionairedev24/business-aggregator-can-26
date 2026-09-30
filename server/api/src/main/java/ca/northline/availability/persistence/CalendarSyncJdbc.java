package ca.northline.availability.persistence;

import ca.northline.availability.application.CalendarGateway.BusyEvent;
import ca.northline.availability.application.CalendarSyncRepository;
import ca.northline.availability.domain.CalendarProvider;
import ca.northline.availability.domain.CalendarScope;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code availability.calendar_*} (S-32) via JdbcClient. */
@Repository
@RequiredArgsConstructor
class CalendarSyncJdbc implements CalendarSyncRepository {

    private final JdbcClient jdbc;

    // ── OAuth requests ───────────────────────────────────────────────────────────

    @Override
    public void saveRequest(OAuthRequest r) {
        jdbc.sql("""
                        insert into availability.calendar_oauth_requests (state_hash, merchant_id, member_user_id, provider,
                               code_verifier, scopes, created_at, expires_at)
                        values (:state, :m, :member, :provider, :verifier, :scopes, :created, :expires)
                        """)
                .param("state", r.stateHash())
                .param("m", r.merchantId())
                .param("member", r.memberUserId())
                .param("provider", r.provider().code())
                .param("verifier", r.codeVerifier())
                .param(
                        "scopes",
                        r.scopes().stream().map(CalendarScope::code).sorted().toArray(String[]::new))
                .param("created", JdbcTimes.ts(r.createdAt()))
                .param("expires", JdbcTimes.ts(r.expiresAt()))
                .update();
    }

    @Override
    public Optional<OAuthRequest> takeRequest(String stateHash, Instant now) {
        return jdbc.sql("""
                        delete from availability.calendar_oauth_requests where state_hash = :state
                        returning state_hash, merchant_id, member_user_id, provider, code_verifier, scopes, created_at,
                                  expires_at
                        """)
                .param("state", stateHash)
                .query((rs, _) -> new OAuthRequest(
                        rs.getString("state_hash"),
                        rs.getString("merchant_id"),
                        rs.getString("member_user_id"),
                        CodedEnum.fromCode(CalendarProvider.class, rs.getString("provider")),
                        rs.getString("code_verifier"),
                        scopes(rs),
                        JdbcTimes.requiredInstant(rs, "created_at"),
                        JdbcTimes.requiredInstant(rs, "expires_at")))
                .optional()
                .filter(r -> r.expiresAt().isAfter(now));
    }

    @Override
    public int purgeRequests(Instant now) {
        return jdbc.sql("delete from availability.calendar_oauth_requests where expires_at < :now")
                .param("now", JdbcTimes.ts(now))
                .update();
    }

    // ── sources ──────────────────────────────────────────────────────────────────

    private static final String SOURCE_COLUMNS = "link_id, calendar_id, name, sync_cursor, window_from, synced_at";

    @Override
    public List<Source> sources(String linkId) {
        return jdbc.sql("select " + SOURCE_COLUMNS
                        + " from availability.calendar_sources where link_id = :link order by name, calendar_id")
                .param("link", linkId)
                .query((rs, _) -> source(rs))
                .list();
    }

    @Override
    public void replaceSources(String linkId, Map<String, String> calendarNames) {
        jdbc.sql("delete from availability.calendar_sources where link_id = :link and not (calendar_id = any(:ids))")
                .param("link", linkId)
                .param("ids", calendarNames.keySet().toArray(String[]::new))
                .update();
        calendarNames.forEach((id, name) -> jdbc.sql("""
                        insert into availability.calendar_sources (link_id, calendar_id, name) values (:link, :id, :name)
                        on conflict (link_id, calendar_id) do update set name = excluded.name
                        """)
                .param("link", linkId)
                .param("id", id)
                .param("name", name)
                .update());
    }

    @Override
    public Optional<Source> lock(String linkId, String calendarId) {
        return jdbc.sql("select " + SOURCE_COLUMNS + """
                         from availability.calendar_sources where link_id = :link and calendar_id = :id
                           for update skip locked
                        """)
                .param("link", linkId)
                .param("id", calendarId)
                .query((rs, _) -> source(rs))
                .optional();
    }

    @Override
    public List<Source> due(Instant before, int limit) {
        return jdbc.sql("""
                        select s.link_id, s.calendar_id, s.name, s.sync_cursor, s.window_from, s.synced_at
                          from availability.calendar_sources s
                          join availability.calendar_links l on l.id = s.link_id
                         where l.state = 'connected' and (s.synced_at is null or s.synced_at < :before)
                         order by s.synced_at nulls first
                         limit :limit
                        """)
                .param("before", JdbcTimes.ts(before))
                .param("limit", limit)
                .query((rs, _) -> source(rs))
                .list();
    }

    @Override
    public void saveCursor(
            String linkId, String calendarId, @Nullable String cursor, @Nullable Instant windowFrom, Instant at) {
        jdbc.sql("""
                        update availability.calendar_sources set sync_cursor = :cursor, window_from = :from, synced_at = :at
                         where link_id = :link and calendar_id = :id
                        """)
                .param("link", linkId)
                .param("id", calendarId)
                .param("cursor", cursor, Types.VARCHAR)
                .param("from", JdbcTimes.ts(windowFrom), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("at", JdbcTimes.ts(at))
                .update();
    }

    // ── busy blocks ──────────────────────────────────────────────────────────────

    @Override
    public void replaceBusy(String linkId, String calendarId, Collection<BusyEvent> events) {
        jdbc.sql("delete from availability.calendar_busy_blocks where link_id = :link and calendar_id = :id")
                .param("link", linkId)
                .param("id", calendarId)
                .update();
        upsertBusy(linkId, calendarId, events);
    }

    @Override
    public void upsertBusy(String linkId, String calendarId, Collection<BusyEvent> events) {
        for (var e : events) {
            jdbc.sql("""
                            insert into availability.calendar_busy_blocks (link_id, calendar_id, external_event_id,
                                   starts_at, ends_at)
                            values (:link, :id, :event, :starts, :ends)
                            on conflict (link_id, calendar_id, external_event_id)
                            do update set starts_at = excluded.starts_at, ends_at = excluded.ends_at
                            """)
                    .param("link", linkId)
                    .param("id", calendarId)
                    .param("event", e.id())
                    .param("starts", JdbcTimes.ts(e.startsAt()))
                    .param("ends", JdbcTimes.ts(e.endsAt()))
                    .update();
        }
    }

    @Override
    public void removeBusy(String linkId, String calendarId, Collection<String> eventIds) {
        if (eventIds.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        delete from availability.calendar_busy_blocks
                         where link_id = :link and calendar_id = :id and external_event_id = any(:events)
                        """)
                .param("link", linkId)
                .param("id", calendarId)
                .param("events", eventIds.toArray(String[]::new))
                .update();
    }

    @Override
    public List<BusyTime> busy(String merchantId, String memberUserId, Instant from, Instant to) {
        return jdbc.sql("""
                        select b.starts_at, b.ends_at
                          from availability.calendar_busy_blocks b
                          join availability.calendar_links l on l.id = b.link_id
                         where l.merchant_id = :m and l.member_user_id = :member
                           and b.starts_at < :to and b.ends_at > :from
                         order by b.starts_at
                        """)
                .param("m", merchantId)
                .param("member", memberUserId)
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .query((rs, _) -> new BusyTime(
                        JdbcTimes.requiredInstant(rs, "starts_at"), JdbcTimes.requiredInstant(rs, "ends_at")))
                .list();
    }

    // ── channels ─────────────────────────────────────────────────────────────────

    private static final String CHANNEL_COLUMNS =
            "id, link_id, calendar_id, provider, external_id, secret_hash, expires_at, created_at";

    @Override
    public void insertChannel(Channel c) {
        jdbc.sql("""
                        insert into availability.calendar_channels (id, link_id, calendar_id, provider, external_id,
                               secret_hash, expires_at, created_at)
                        values (:id, :link, :calendar, :provider, :external, :secret, :expires, :created)
                        """)
                .param("id", c.id())
                .param("link", c.linkId())
                .param("calendar", c.calendarId())
                .param("provider", c.provider().code())
                .param("external", c.externalId(), Types.VARCHAR)
                .param("secret", c.secretHash())
                .param("expires", JdbcTimes.ts(c.expiresAt()))
                .param("created", JdbcTimes.ts(c.createdAt()))
                .update();
    }

    @Override
    public void activateChannel(String channelId, String externalId, Instant expiresAt) {
        jdbc.sql(
                        "update availability.calendar_channels set external_id = :external, expires_at = :expires where id = :id")
                .param("id", channelId)
                .param("external", externalId)
                .param("expires", JdbcTimes.ts(expiresAt))
                .update();
    }

    @Override
    public void deleteChannel(String channelId) {
        jdbc.sql("delete from availability.calendar_channels where id = :id")
                .param("id", channelId)
                .update();
    }

    @Override
    public Optional<Channel> channel(String channelId) {
        return jdbc.sql("select " + CHANNEL_COLUMNS + " from availability.calendar_channels where id = :id")
                .param("id", channelId)
                .query((rs, _) -> channel(rs))
                .optional();
    }

    @Override
    public Optional<Channel> channelByExternalId(CalendarProvider provider, String externalId) {
        return jdbc.sql("select " + CHANNEL_COLUMNS
                        + " from availability.calendar_channels where provider = :provider and external_id = :external")
                .param("provider", provider.code())
                .param("external", externalId)
                .query((rs, _) -> channel(rs))
                .optional();
    }

    @Override
    public List<Channel> channels(String linkId) {
        return jdbc.sql("select " + CHANNEL_COLUMNS + " from availability.calendar_channels where link_id = :link")
                .param("link", linkId)
                .query((rs, _) -> channel(rs))
                .list();
    }

    @Override
    public List<Channel> expiringBefore(Instant before, int limit) {
        return jdbc.sql(
                        "select " + CHANNEL_COLUMNS
                                + " from availability.calendar_channels where expires_at < :before order by expires_at limit :limit")
                .param("before", JdbcTimes.ts(before))
                .param("limit", limit)
                .query((rs, _) -> channel(rs))
                .list();
    }

    @Override
    public boolean pendingChannel(CalendarProvider provider, Instant since) {
        return jdbc.sql("""
                        select exists(select 1 from availability.calendar_channels
                                       where provider = :provider and external_id is null and created_at >= :since)
                        """)
                .param("provider", provider.code())
                .param("since", JdbcTimes.ts(since))
                .query(Boolean.class)
                .single();
    }

    // ── notifications ────────────────────────────────────────────────────────────

    @Override
    public boolean recordNotification(CalendarProvider provider, String dedupeKey, String channelId, Instant at) {
        return jdbc.sql("""
                                insert into availability.calendar_notifications (provider, dedupe_key, channel_id, received_at)
                                values (:provider, :key, :channel, :at) on conflict do nothing
                                """)
                        .param("provider", provider.code())
                        .param("key", dedupeKey)
                        .param("channel", channelId)
                        .param("at", JdbcTimes.ts(at))
                        .update()
                == 1;
    }

    @Override
    public int purgeNotifications(Instant before) {
        return jdbc.sql("delete from availability.calendar_notifications where received_at < :before")
                .param("before", JdbcTimes.ts(before))
                .update();
    }

    // ── write-back ───────────────────────────────────────────────────────────────

    @Override
    public Map<String, Mirror> mirrors(String linkId) {
        var mirrors = new LinkedHashMap<String, Mirror>();
        jdbc.sql("""
                        select link_id, booking_id, calendar_id, external_event_id, starts_at, ends_at, content_hash,
                               written_at
                          from availability.calendar_event_mirrors where link_id = :link order by starts_at
                        """)
                .param("link", linkId)
                .query((rs, _) -> new Mirror(
                        rs.getString("link_id"),
                        rs.getString("booking_id"),
                        rs.getString("calendar_id"),
                        rs.getString("external_event_id"),
                        JdbcTimes.requiredInstant(rs, "starts_at"),
                        JdbcTimes.requiredInstant(rs, "ends_at"),
                        rs.getString("content_hash"),
                        JdbcTimes.requiredInstant(rs, "written_at")))
                .list()
                .forEach(m -> mirrors.put(m.bookingId(), m));
        return mirrors;
    }

    @Override
    public Set<String> mirroredEventIds(String linkId) {
        return new HashSet<>(
                jdbc.sql("select external_event_id from availability.calendar_event_mirrors where link_id = :link")
                        .param("link", linkId)
                        .query(String.class)
                        .list());
    }

    @Override
    public void saveMirror(Mirror m) {
        jdbc.sql("""
                        insert into availability.calendar_event_mirrors (link_id, booking_id, calendar_id, external_event_id,
                               starts_at, ends_at, content_hash, written_at)
                        values (:link, :booking, :calendar, :event, :starts, :ends, :hash, :at)
                        on conflict (link_id, booking_id) do update set calendar_id = excluded.calendar_id,
                               external_event_id = excluded.external_event_id, starts_at = excluded.starts_at,
                               ends_at = excluded.ends_at, content_hash = excluded.content_hash,
                               written_at = excluded.written_at
                        """)
                .param("link", m.linkId())
                .param("booking", m.bookingId())
                .param("calendar", m.calendarId())
                .param("event", m.externalEventId())
                .param("starts", JdbcTimes.ts(m.startsAt()))
                .param("ends", JdbcTimes.ts(m.endsAt()))
                .param("hash", m.contentHash())
                .param("at", JdbcTimes.ts(m.writtenAt()))
                .update();
    }

    @Override
    public void deleteMirror(String linkId, String bookingId) {
        jdbc.sql("delete from availability.calendar_event_mirrors where link_id = :link and booking_id = :booking")
                .param("link", linkId)
                .param("booking", bookingId)
                .update();
    }

    // ── mapping ──────────────────────────────────────────────────────────────────

    private static Source source(ResultSet rs) throws SQLException {
        return new Source(
                rs.getString("link_id"),
                rs.getString("calendar_id"),
                rs.getString("name"),
                rs.getString("sync_cursor"),
                JdbcTimes.instant(rs, "window_from"),
                JdbcTimes.instant(rs, "synced_at"));
    }

    private static Channel channel(ResultSet rs) throws SQLException {
        return new Channel(
                rs.getString("id"),
                rs.getString("link_id"),
                rs.getString("calendar_id"),
                CodedEnum.fromCode(CalendarProvider.class, rs.getString("provider")),
                rs.getString("external_id"),
                rs.getString("secret_hash"),
                JdbcTimes.requiredInstant(rs, "expires_at"),
                JdbcTimes.requiredInstant(rs, "created_at"));
    }

    private static Set<CalendarScope> scopes(ResultSet rs) throws SQLException {
        var scopes = EnumSet.noneOf(CalendarScope.class);
        var array = rs.getArray("scopes");
        if (array != null) {
            for (var code : (String[]) array.getArray()) {
                scopes.add(CodedEnum.fromCode(CalendarScope.class, code));
            }
        }
        return scopes;
    }
}
