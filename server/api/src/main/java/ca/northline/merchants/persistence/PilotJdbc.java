package ca.northline.merchants.persistence;

import static ca.northline.shared.JdbcTimes.instant;
import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.merchants.api.PilotCohort.Note;
import ca.northline.merchants.application.PilotStore;
import ca.northline.merchants.domain.PilotInvite;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link PilotStore} over {@code merchants.pilot_businesses}, {@code pilot_invites}, {@code pilot_notes} (V320). */
@Repository
@RequiredArgsConstructor
class PilotJdbc implements PilotStore {

    private static final String PILOT = """
            select id, market_id, business_type, label, merchant_id, owner_id, blocker, blocker_owner, blocker_since,
                   created_by, created_at
              from merchants.pilot_businesses
            """;
    private static final String INVITE = """
            select id, pilot_id, email, sent_by, created_at, expires_at, accepted_at, revoked_at
              from merchants.pilot_invites
            """;

    private final JdbcClient jdbc;

    @Override
    public void insert(PilotRow row) {
        jdbc.sql("""
                        insert into merchants.pilot_businesses (id, market_id, business_type, label, merchant_id,
                               owner_id, created_by, created_at, updated_at)
                        values (:id, :market, :type, :label, :merchant, :owner, :by, :at, :at)
                        """)
                .param("id", row.id())
                .param("market", row.marketId())
                .param("type", row.businessType())
                .param("label", row.label())
                .param("merchant", row.merchantId())
                .param("owner", row.ownerId())
                .param("by", row.createdBy())
                .param("at", ts(row.createdAt()))
                .update();
    }

    @Override
    public Optional<PilotRow> lock(String pilotId) {
        return jdbc.sql(PILOT + " where id = :id for update")
                .param("id", pilotId)
                .query((rs, _) -> pilot(rs))
                .optional();
    }

    @Override
    public Optional<PilotRow> pilot(String pilotId) {
        return jdbc.sql(PILOT + " where id = :id")
                .param("id", pilotId)
                .query((rs, _) -> pilot(rs))
                .optional();
    }

    @Override
    public Optional<PilotRow> byMerchant(String merchantId) {
        return jdbc.sql(PILOT + " where merchant_id = :m")
                .param("m", merchantId)
                .query((rs, _) -> pilot(rs))
                .optional();
    }

    @Override
    public List<PilotRow> pilots(@Nullable String marketId) {
        return jdbc.sql(PILOT + " where (cast(:market as text) is null or market_id = :market) order by created_at, id")
                .param("market", marketId)
                .query((rs, _) -> pilot(rs))
                .list();
    }

    @Override
    public void linkMerchant(String pilotId, String merchantId, Instant at) {
        jdbc.sql("update merchants.pilot_businesses set merchant_id = :m, updated_at = :at where id = :id")
                .param("m", merchantId)
                .param("at", ts(at))
                .param("id", pilotId)
                .update();
    }

    @Override
    public void owner(String pilotId, @Nullable String ownerId, Instant at) {
        jdbc.sql("update merchants.pilot_businesses set owner_id = :owner, updated_at = :at where id = :id")
                .param("owner", ownerId)
                .param("at", ts(at))
                .param("id", pilotId)
                .update();
    }

    @Override
    public void blocker(
            String pilotId, @Nullable String text, @Nullable String owner, @Nullable Instant since, Instant at) {
        jdbc.sql("""
                        update merchants.pilot_businesses
                           set blocker = :text, blocker_owner = :owner, blocker_since = :since, updated_at = :at
                         where id = :id
                        """)
                .param("text", text)
                .param("owner", owner)
                .param("since", ts(since))
                .param("at", ts(at))
                .param("id", pilotId)
                .update();
    }

    @Override
    public void insertInvite(PilotInvite invite, String tokenHash) {
        jdbc.sql("""
                        insert into merchants.pilot_invites (id, pilot_id, token_hash, email, sent_by, created_at,
                               expires_at)
                        values (:id, :pilot, :hash, :email, :by, :at, :expires)
                        """)
                .param("id", invite.id())
                .param("pilot", invite.pilotId())
                .param("hash", tokenHash)
                .param("email", invite.email())
                .param("by", invite.sentBy())
                .param("at", ts(invite.createdAt()))
                .param("expires", ts(invite.expiresAt()))
                .update();
    }

    @Override
    public List<PilotInvite> invites(String pilotId) {
        return jdbc.sql(INVITE + " where pilot_id = :p order by created_at desc, id desc")
                .param("p", pilotId)
                .query((rs, _) -> invite(rs))
                .list();
    }

    @Override
    public Map<String, PilotInvite> latestInvites(Collection<String> pilotIds) {
        if (pilotIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("""
                        select distinct on (pilot_id) id, pilot_id, email, sent_by, created_at, expires_at,
                               accepted_at, revoked_at
                          from merchants.pilot_invites where pilot_id = any(:p)
                         order by pilot_id, created_at desc, id desc
                        """).param("p", pilotIds.toArray(String[]::new)).query((rs, _) -> invite(rs)).list().stream()
                .collect(Collectors.toUnmodifiableMap(PilotInvite::pilotId, i -> i));
    }

    @Override
    public Optional<PilotInvite> inviteByTokenHash(String hash) {
        return jdbc.sql(INVITE + " where token_hash = :h")
                .param("h", hash)
                .query((rs, _) -> invite(rs))
                .optional();
    }

    @Override
    public void acceptInvite(String inviteId, String userId, Instant at) {
        jdbc.sql("update merchants.pilot_invites set accepted_at = :at, accepted_by = :u where id = :id")
                .param("at", ts(at))
                .param("u", userId)
                .param("id", inviteId)
                .update();
    }

    @Override
    public void revokePending(String pilotId, Instant at) {
        jdbc.sql("""
                        update merchants.pilot_invites set revoked_at = :at
                         where pilot_id = :p and accepted_at is null and revoked_at is null
                        """).param("at", ts(at)).param("p", pilotId).update();
    }

    @Override
    public void insertNote(String pilotId, Note note) {
        jdbc.sql("""
                        insert into merchants.pilot_notes (id, pilot_id, author_id, body, created_at)
                        values (:id, :p, :author, :body, :at)
                        """)
                .param("id", note.id())
                .param("p", pilotId)
                .param("author", note.authorId())
                .param("body", note.body())
                .param("at", ts(note.createdAt()))
                .update();
    }

    @Override
    public List<Note> notes(String pilotId) {
        return jdbc.sql("""
                        select id, author_id, body, created_at from merchants.pilot_notes
                         where pilot_id = :p order by created_at desc, id desc
                        """)
                .param("p", pilotId)
                .query((rs, _) -> new Note(
                        rs.getString("id"),
                        rs.getString("author_id"),
                        rs.getString("body"),
                        requiredInstant(rs, "created_at")))
                .list();
    }

    @Override
    public Map<String, Facts> facts(Collection<String> merchantIds) {
        if (merchantIds.isEmpty()) {
            return Map.of();
        }
        return jdbc
                .sql("""
                        select m.id, m.display_name, m.type, m.status, m.onboarding_step, m.province, m.city,
                               m.legal_name <> '' as details_complete, m.submitted_at, m.approved_at,
                               m.search_hidden_cause,
                               (select count(*) from merchants.verifications v where v.merchant_id = m.id) as total,
                               (select count(*) from merchants.verifications v
                                 where v.merchant_id = m.id and v.status in ('submitted', 'verified')) as complete,
                               coalesce(array(select v.check_key from merchants.verifications v
                                               where v.merchant_id = m.id and v.status = 'rejected'
                                               order by v.position), '{}') as rejected,
                               (select v.status from merchants.verifications v
                                 where v.merchant_id = m.id and v.check_key = 'kyc') as kyc,
                               exists (select 1 from merchants.owner_identity_checks o
                                        where o.merchant_id = m.id and o.status = 'review') as identity_review,
                               (select count(*) from merchants.registry_checks r
                                 where r.merchant_id = m.id and r.review_state = 'open') as registry_open,
                               sv.status as site_visit, sv.reference as site_visit_ref,
                               exists (select 1 from merchants.storefronts s
                                        where s.merchant_id = m.id and s.published_at is not null) as published,
                               coalesce(array(select c.category_id from merchants.merchant_categories c
                                               where c.merchant_id = m.id and c.status <> 'rejected'
                                               order by c.category_id), '{}') as categories
                          from merchants.merchants m
                          left join merchants.verifications sv on sv.merchant_id = m.id and sv.check_key = 'site_visit'
                         where m.id = any(:ids)
                        """)
                .param("ids", merchantIds.toArray(String[]::new))
                .query((rs, _) -> new Facts(
                        rs.getString("id"),
                        rs.getString("display_name"),
                        rs.getString("type"),
                        rs.getString("status"),
                        rs.getString("onboarding_step"),
                        rs.getString("province"),
                        rs.getString("city"),
                        rs.getBoolean("details_complete"),
                        rs.getInt("complete"),
                        rs.getInt("total"),
                        strings(rs.getArray("rejected")),
                        rs.getString("kyc"),
                        rs.getBoolean("identity_review"),
                        rs.getInt("registry_open"),
                        rs.getString("site_visit"),
                        rs.getString("site_visit_ref"),
                        instant(rs, "submitted_at"),
                        instant(rs, "approved_at"),
                        rs.getBoolean("published"),
                        rs.getString("search_hidden_cause"),
                        strings(rs.getArray("categories"))))
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableMap(Facts::merchantId, f -> f));
    }

    @Override
    public Map<String, @Nullable String> marketBusinesses(String province, String city) {
        var out = new java.util.LinkedHashMap<String, @Nullable String>();
        jdbc.sql("""
                        select id, search_hidden_cause from merchants.merchants
                         where province = :p and lower(city) = lower(:c) order by id
                        """)
                .param("p", province)
                .param("c", city)
                .query((rs, _) -> {
                    out.put(rs.getString("id"), rs.getString("search_hidden_cause"));
                    return null;
                })
                .list();
        return java.util.Collections.unmodifiableMap(out);
    }

    @Override
    public void hideBeforeLaunch(String merchantId, Instant at) {
        jdbc.sql("""
                        update merchants.merchants set search_hidden_at = :at, search_hidden_cause = 'pilot', updated_at = :at
                         where id = :m and search_hidden_at is null
                        """).param("at", ts(at)).param("m", merchantId).update();
    }

    private static PilotRow pilot(ResultSet rs) throws SQLException {
        return new PilotRow(
                rs.getString("id"),
                rs.getString("market_id"),
                rs.getString("business_type"),
                rs.getString("label"),
                rs.getString("merchant_id"),
                rs.getString("owner_id"),
                rs.getString("blocker"),
                rs.getString("blocker_owner"),
                instant(rs, "blocker_since"),
                rs.getString("created_by"),
                requiredInstant(rs, "created_at"));
    }

    private static PilotInvite invite(ResultSet rs) throws SQLException {
        return new PilotInvite(
                rs.getString("id"),
                rs.getString("pilot_id"),
                rs.getString("email"),
                rs.getString("sent_by"),
                requiredInstant(rs, "created_at"),
                requiredInstant(rs, "expires_at"),
                instant(rs, "accepted_at"),
                instant(rs, "revoked_at"));
    }

    private static List<String> strings(@Nullable Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
