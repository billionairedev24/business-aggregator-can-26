package ca.northline.availability.persistence;

import ca.northline.availability.application.CalendarLinkRepository;
import ca.northline.availability.application.HoursRepository;
import ca.northline.availability.application.TimeOffRepository;
import ca.northline.availability.domain.BookingRules;
import ca.northline.availability.domain.CalendarLinkState;
import ca.northline.availability.domain.CalendarProvider;
import ca.northline.availability.domain.CalendarScope;
import ca.northline.availability.domain.TimeOff;
import ca.northline.availability.domain.TimeRange;
import ca.northline.availability.domain.WeeklyHours;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.Ids;
import ca.northline.shared.JdbcTimes;
import ca.northline.shared.crypto.SecretSealer.Sealed;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code availability.*} via JdbcClient. Weekly hours are stored as one row per member, ISO weekday (1 = Mon) and
 * effective date; a day without hours is stored as {@code []} so a later schedule can close a day.
 */
@Repository
@RequiredArgsConstructor
class AvailabilityJdbc implements HoursRepository, TimeOffRepository, CalendarLinkRepository {

    private final JdbcClient jdbc;
    private final RangesJson ranges;

    // ── hours ───────────────────────────────────────────────────────────────────

    @Override
    public List<SavedHours> latest(String merchantId) {
        var rows = jdbc.sql("""
                        select r.member_user_id, r.effective_from, r.weekday, r.ranges::text as ranges
                          from availability.availability_rules r
                         where r.merchant_id = :merchantId
                           and r.effective_from = (select max(x.effective_from) from availability.availability_rules x
                                                    where x.merchant_id = r.merchant_id
                                                      and x.member_user_id = r.member_user_id)
                         order by r.member_user_id, r.weekday
                        """)
                .param("merchantId", merchantId)
                .query((rs, _) -> new DayRow(
                        rs.getString("member_user_id"),
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getInt("weekday"),
                        ranges.read(rs.getString("ranges"))))
                .list();
        var byMember = new LinkedHashMap<String, List<DayRow>>();
        rows.forEach(r -> byMember.computeIfAbsent(r.member(), _ -> new java.util.ArrayList<>())
                .add(r));
        return byMember.entrySet().stream()
                .map(e -> new SavedHours(e.getKey(), e.getValue().getFirst().effectiveFrom(), hours(e.getValue())))
                .toList();
    }

    @Override
    public Optional<WeeklyHours> effectiveOn(String merchantId, String memberUserId, LocalDate day) {
        var rows = jdbc.sql("""
                        select r.member_user_id, r.effective_from, r.weekday, r.ranges::text as ranges
                          from availability.availability_rules r
                         where r.merchant_id = :merchantId and r.member_user_id = :member
                           and r.effective_from = (select max(x.effective_from) from availability.availability_rules x
                                                    where x.merchant_id = r.merchant_id and x.member_user_id = r.member_user_id
                                                      and x.effective_from <= :day)
                        """)
                .param("merchantId", merchantId)
                .param("member", memberUserId)
                .param("day", day)
                .query((rs, _) -> new DayRow(
                        rs.getString("member_user_id"),
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getInt("weekday"),
                        ranges.read(rs.getString("ranges"))))
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(hours(rows));
    }

    @Override
    public void replace(
            String merchantId, String memberUserId, LocalDate effectiveFrom, WeeklyHours hours, Instant at) {
        jdbc.sql("""
                        delete from availability.availability_rules
                         where merchant_id = :merchantId and member_user_id = :member and effective_from >= :from
                        """)
                .param("merchantId", merchantId)
                .param("member", memberUserId)
                .param("from", effectiveFrom)
                .update();
        for (var day : DayOfWeek.values()) {
            jdbc.sql("""
                            insert into availability.availability_rules
                                   (id, merchant_id, member_user_id, weekday, ranges, effective_from, updated_at)
                            values (:id, :merchantId, :member, :weekday, cast(:ranges as jsonb), :from, :at)
                            """)
                    .param("id", Ids.next())
                    .param("merchantId", merchantId)
                    .param("member", memberUserId)
                    .param("weekday", day.getValue())
                    .param("ranges", ranges.write(hours.on(day)))
                    .param("from", effectiveFrom)
                    .param("at", JdbcTimes.ts(at))
                    .update();
        }
    }

    @Override
    public Optional<Instant> lastSaved(String merchantId) {
        return Optional.ofNullable(jdbc.sql("""
                        select greatest((select max(updated_at) from availability.availability_rules where merchant_id = :m),
                                        (select max(updated_at) from availability.booking_rules where merchant_id = :m))
                               as saved
                        """)
                .param("m", merchantId)
                .query((rs, _) -> JdbcTimes.instant(rs, "saved"))
                .single());
    }

    // ── rules ───────────────────────────────────────────────────────────────────

    @Override
    public Optional<BookingRules> rules(String merchantId) {
        var areas = jdbc.sql("select zone from availability.service_areas where merchant_id = :m order by zone")
                .param("m", merchantId)
                .query((rs, _) -> rs.getString("zone"))
                .list();
        return jdbc.sql("select * from availability.booking_rules where merchant_id = :m")
                .param("m", merchantId)
                .query((rs, _) -> new BookingRules(
                        rs.getInt("interval_min"),
                        rs.getInt("buffer_min"),
                        rs.getInt("min_notice_min"),
                        integer(rs, "same_day_cutoff_min"),
                        rs.getInt("horizon_days"),
                        rs.getInt("max_jobs_per_day"),
                        CodedEnum.fromCode(BookingRules.AcceptMode.class, rs.getString("accept_mode")),
                        rs.getInt("reschedule_free_min"),
                        longValue(rs, "late_cancel_fee_cents"),
                        integer(rs, "late_cancel_fee_bps"),
                        longValue(rs, "emergency_premium_cents"),
                        integer(rs, "emergency_premium_bps"),
                        rs.getLong("holiday_premium_cents"),
                        new java.util.HashSet<>(areas)))
                .optional();
    }

    @Override
    public void saveRules(String merchantId, BookingRules r, Instant at) {
        jdbc.sql("""
                        insert into availability.booking_rules (merchant_id, interval_min, buffer_min, min_notice_min,
                               same_day_cutoff_min, horizon_days, max_jobs_per_day, accept_mode, reschedule_free_min,
                               late_cancel_fee_cents, late_cancel_fee_bps, emergency_premium_cents, emergency_premium_bps,
                               holiday_premium_cents, updated_at)
                        values (:m, :interval, :buffer, :notice, :cutoff, :horizon, :maxJobs, :accept, :reschedule,
                               :lateCents, :lateBps, :premCents, :premBps, :holiday, :at)
                        on conflict (merchant_id) do update set interval_min = excluded.interval_min,
                               buffer_min = excluded.buffer_min, min_notice_min = excluded.min_notice_min,
                               same_day_cutoff_min = excluded.same_day_cutoff_min, horizon_days = excluded.horizon_days,
                               max_jobs_per_day = excluded.max_jobs_per_day, accept_mode = excluded.accept_mode,
                               reschedule_free_min = excluded.reschedule_free_min,
                               late_cancel_fee_cents = excluded.late_cancel_fee_cents,
                               late_cancel_fee_bps = excluded.late_cancel_fee_bps,
                               emergency_premium_cents = excluded.emergency_premium_cents,
                               emergency_premium_bps = excluded.emergency_premium_bps,
                               holiday_premium_cents = excluded.holiday_premium_cents, updated_at = excluded.updated_at
                        """)
                .param("m", merchantId)
                .param("interval", r.intervalMin())
                .param("buffer", r.bufferMin())
                .param("notice", r.minNoticeMin())
                .param("cutoff", r.sameDayCutoffMin(), Types.INTEGER)
                .param("horizon", r.horizonDays())
                .param("maxJobs", r.maxJobsPerDay())
                .param("accept", r.acceptMode().code())
                .param("reschedule", r.rescheduleFreeMin())
                .param("lateCents", r.lateCancelFeeCents(), Types.BIGINT)
                .param("lateBps", r.lateCancelFeeBps(), Types.INTEGER)
                .param("premCents", r.emergencyPremiumCents(), Types.BIGINT)
                .param("premBps", r.emergencyPremiumBps(), Types.INTEGER)
                .param("holiday", r.holidayPremiumCents())
                .param("at", JdbcTimes.ts(at))
                .update();
        jdbc.sql("delete from availability.service_areas where merchant_id = :m")
                .param("m", merchantId)
                .update();
        for (var zone : r.serviceAreas()) {
            jdbc.sql("insert into availability.service_areas (merchant_id, zone) values (:m, :zone)")
                    .param("m", merchantId)
                    .param("zone", zone)
                    .update();
        }
    }

    @Override
    public Set<LocalDate> openHolidays(String merchantId) {
        return new HashSet<>(jdbc.sql("select holiday_date from availability.holiday_openings where merchant_id = :m")
                .param("m", merchantId)
                .query((rs, _) -> rs.getObject("holiday_date", LocalDate.class))
                .list());
    }

    @Override
    public void setHolidayOpen(String merchantId, LocalDate date, boolean open) {
        jdbc.sql(
                        open
                                ? "insert into availability.holiday_openings (merchant_id, holiday_date) values (:m, :d) on conflict do nothing"
                                : "delete from availability.holiday_openings where merchant_id = :m and holiday_date = :d")
                .param("m", merchantId)
                .param("d", date)
                .update();
    }

    // ── time off ────────────────────────────────────────────────────────────────

    @Override
    public List<TimeOff> from(String merchantId, LocalDate from) {
        return jdbc.sql("""
                        select id, member_user_id, starts_on, ends_on, kind, special_ranges::text as special, reason
                          from availability.time_off where merchant_id = :m and ends_on >= :from
                         order by starts_on, id
                        """)
                .param("m", merchantId)
                .param("from", from)
                .query((rs, _) -> new TimeOff(
                        rs.getString("id"),
                        rs.getString("member_user_id"),
                        rs.getObject("starts_on", LocalDate.class),
                        rs.getObject("ends_on", LocalDate.class),
                        CodedEnum.fromCode(TimeOff.Kind.class, rs.getString("kind")),
                        ranges.read(rs.getString("special")),
                        rs.getString("reason")))
                .list();
    }

    @Override
    public void insert(String merchantId, TimeOff t, String actorId, Instant at) {
        jdbc.sql("""
                        insert into availability.time_off (id, merchant_id, member_user_id, starts_on, ends_on, kind,
                               special_ranges, reason, created_at, created_by)
                        values (:id, :m, :member, :starts, :ends, :kind, cast(:special as jsonb), :reason, :at, :by)
                        """)
                .param("id", t.id())
                .param("m", merchantId)
                .param("member", t.memberUserId(), Types.VARCHAR)
                .param("starts", t.startsOn())
                .param("ends", t.endsOn())
                .param("kind", t.kind().code())
                .param(
                        "special",
                        t.kind() == TimeOff.Kind.SPECIAL ? ranges.write(t.specialRanges()) : null,
                        Types.VARCHAR)
                .param("reason", t.reason(), Types.VARCHAR)
                .param("at", JdbcTimes.ts(at))
                .param("by", actorId)
                .update();
    }

    @Override
    public boolean delete(String merchantId, String id) {
        return jdbc.sql("delete from availability.time_off where merchant_id = :m and id = :id")
                        .param("m", merchantId)
                        .param("id", id)
                        .update()
                > 0;
    }

    // ── calendar links ──────────────────────────────────────────────────────────

    private static final String LINK_COLUMNS = """
            id, merchant_id, member_user_id, provider, account_label, token_ref, connected_at, last_sync_at, state,
            scopes, external_account_id, write_calendar_id""";

    @Override
    public List<Link> of(String merchantId, String memberUserId) {
        return jdbc.sql("select " + LINK_COLUMNS + """
                         from availability.calendar_links where merchant_id = :m and member_user_id = :member
                        """)
                .param("m", merchantId)
                .param("member", memberUserId)
                .query((rs, _) -> link(rs))
                .list();
    }

    @Override
    public Optional<Link> find(String merchantId, String memberUserId, CalendarProvider provider) {
        return of(merchantId, memberUserId).stream()
                .filter(l -> l.provider() == provider)
                .findFirst();
    }

    @Override
    public Optional<Link> byId(String linkId) {
        return jdbc.sql("select " + LINK_COLUMNS + " from availability.calendar_links where id = :id")
                .param("id", linkId)
                .query((rs, _) -> link(rs))
                .optional();
    }

    @Override
    public Optional<Link> lock(String linkId) {
        return jdbc.sql("select " + LINK_COLUMNS
                        + " from availability.calendar_links where id = :id for update skip locked")
                .param("id", linkId)
                .query((rs, _) -> link(rs))
                .optional();
    }

    @Override
    public List<Link> connected() {
        return jdbc.sql("select " + LINK_COLUMNS + """
                         from availability.calendar_links
                        where provider in ('google', 'outlook') and state = 'connected' and refresh_token_enc is not null
                        order by id
                        """)
                .query((rs, _) -> link(rs))
                .list();
    }

    @Override
    public void upsert(String merchantId, Link l) {
        jdbc.sql("""
                        insert into availability.calendar_links (id, merchant_id, member_user_id, provider, account_label,
                               mode, token_ref, connected_at, last_sync_at)
                        values (:id, :m, :member, :provider, :label, :mode, :token, :connected, :sync)
                        on conflict (merchant_id, member_user_id, provider) do update set account_label = excluded.account_label,
                               mode = excluded.mode, token_ref = excluded.token_ref, connected_at = excluded.connected_at
                        """)
                .param("id", l.id())
                .param("m", merchantId)
                .param("member", l.memberUserId())
                .param("provider", l.provider().code())
                .param("label", l.accountLabel())
                .param("mode", l.provider().twoWay() ? "two_way" : "read_only")
                .param("token", l.tokenRef())
                .param("connected", JdbcTimes.ts(l.connectedAt()))
                .param("sync", JdbcTimes.ts(l.lastSyncAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    @Override
    public Link saveGrant(Link l, Sealed token) {
        jdbc.sql("""
                        insert into availability.calendar_links (id, merchant_id, member_user_id, provider, account_label,
                               mode, token_ref, connected_at, last_sync_at, state, scopes, external_account_id,
                               refresh_token_enc, refresh_token_key, write_calendar_id, last_error, state_changed_at)
                        values (:id, :m, :member, :provider, :label, 'two_way', :tokenRef, :connected, :sync, 'connected',
                               :scopes, :subject, :enc, :key, :write, null, :connected)
                        on conflict (merchant_id, member_user_id, provider) do update set
                               account_label = excluded.account_label, token_ref = excluded.token_ref,
                               state = 'connected', scopes = excluded.scopes,
                               external_account_id = excluded.external_account_id,
                               refresh_token_enc = excluded.refresh_token_enc,
                               refresh_token_key = excluded.refresh_token_key,
                               write_calendar_id = excluded.write_calendar_id, last_error = null,
                               state_changed_at = now()
                        """)
                .param("id", l.id())
                .param("m", l.merchantId())
                .param("member", l.memberUserId())
                .param("provider", l.provider().code())
                .param("label", l.accountLabel())
                .param("tokenRef", l.tokenRef())
                .param("connected", JdbcTimes.ts(l.connectedAt()))
                .param("sync", JdbcTimes.ts(l.lastSyncAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param(
                        "scopes",
                        l.scopes().stream().map(CalendarScope::code).sorted().toArray(String[]::new))
                .param("subject", l.externalAccountId(), Types.VARCHAR)
                .param("enc", token.ciphertext())
                .param("key", token.wrappedKey())
                .param("write", l.writeCalendarId(), Types.VARCHAR)
                .update();
        return find(l.merchantId(), l.memberUserId(), l.provider()).orElseThrow();
    }

    @Override
    public Optional<Sealed> refreshToken(String linkId) {
        return jdbc.sql("""
                        select token_ref, refresh_token_key, refresh_token_enc from availability.calendar_links
                         where id = :id and refresh_token_enc is not null
                        """)
                .param("id", linkId)
                .query((rs, _) -> new Sealed(
                        rs.getString("token_ref"), rs.getBytes("refresh_token_key"), rs.getBytes("refresh_token_enc")))
                .optional();
    }

    @Override
    public void replaceRefreshToken(String linkId, Sealed token) {
        jdbc.sql("""
                        update availability.calendar_links set token_ref = :ref, refresh_token_key = :key,
                               refresh_token_enc = :enc
                         where id = :id
                        """)
                .param("id", linkId)
                .param("ref", token.keyRef())
                .param("key", token.wrappedKey())
                .param("enc", token.ciphertext())
                .update();
    }

    @Override
    public void markReconnect(String linkId, String error, Instant at) {
        jdbc.sql("""
                        update availability.calendar_links set state = 'reconnect', last_error = :error, state_changed_at = :at
                         where id = :id and state <> 'reconnect'
                        """)
                .param("id", linkId)
                .param("error", error)
                .param("at", JdbcTimes.ts(at))
                .update();
    }

    @Override
    public void synced(String linkId, Instant at) {
        jdbc.sql("update availability.calendar_links set last_sync_at = :at where id = :id")
                .param("id", linkId)
                .param("at", JdbcTimes.ts(at))
                .update();
    }

    @Override
    public void delete(String merchantId, String memberUserId, CalendarProvider provider) {
        jdbc.sql("""
                        delete from availability.calendar_links
                         where merchant_id = :m and member_user_id = :member and provider = :provider
                        """)
                .param("m", merchantId)
                .param("member", memberUserId)
                .param("provider", provider.code())
                .update();
    }

    private static Link link(ResultSet rs) throws SQLException {
        var scopes = EnumSet.noneOf(CalendarScope.class);
        var array = rs.getArray("scopes");
        if (array != null) {
            for (var code : (String[]) array.getArray()) {
                scopes.add(CodedEnum.fromCode(CalendarScope.class, code));
            }
        }
        return new Link(
                rs.getString("id"),
                java.util.Objects.requireNonNullElse(rs.getString("merchant_id"), ""),
                rs.getString("member_user_id"),
                CodedEnum.fromCode(CalendarProvider.class, rs.getString("provider")),
                java.util.Objects.requireNonNullElse(rs.getString("account_label"), ""),
                java.util.Objects.requireNonNullElse(rs.getString("token_ref"), ""),
                java.util.Objects.requireNonNullElse(JdbcTimes.instant(rs, "connected_at"), Instant.EPOCH),
                JdbcTimes.instant(rs, "last_sync_at"),
                CodedEnum.fromCode(CalendarLinkState.class, rs.getString("state")),
                scopes,
                rs.getString("external_account_id"),
                rs.getString("write_calendar_id"));
    }

    private record DayRow(String member, LocalDate effectiveFrom, int weekday, List<TimeRange> ranges) {}

    private static WeeklyHours hours(List<DayRow> rows) {
        var days = new EnumMap<DayOfWeek, List<TimeRange>>(DayOfWeek.class);
        rows.forEach(r -> days.put(DayOfWeek.of(r.weekday()), r.ranges()));
        return new WeeklyHours(days);
    }

    private static @Nullable Integer integer(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static @Nullable Long longValue(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }
}
