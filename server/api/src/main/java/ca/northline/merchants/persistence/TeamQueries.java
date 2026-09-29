package ca.northline.merchants.persistence;

import static ca.northline.shared.JdbcTimes.instant;
import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.merchants.application.TeamStore;
import ca.northline.merchants.domain.TeamInvitation;
import ca.northline.merchants.domain.TeamRules;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.security.MerchantRole;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link TeamStore} over {@code merchants.merchant_members} and {@code merchants.member_invitations}. */
@Repository
@RequiredArgsConstructor
class TeamQueries implements TeamStore {

    private final JdbcClient jdbc;

    @Override
    public List<Seat> seats(String merchantId) {
        return jdbc.sql("""
                        select user_id, role, joined_at from merchants.merchant_members where merchant_id = :m
                         order by (role = 'owner') desc, joined_at, user_id
                        """).param("m", merchantId).query((rs, _) -> seat(rs)).list();
    }

    @Override
    public Optional<Seat> seat(String merchantId, String userId) {
        return jdbc.sql("""
                        select user_id, role, joined_at from merchants.merchant_members
                         where merchant_id = :m and user_id = :u
                        """)
                .param("m", merchantId)
                .param("u", userId)
                .query((rs, _) -> seat(rs))
                .optional();
    }

    @Override
    public long owners(String merchantId) {
        return jdbc.sql("select count(*) from merchants.merchant_members where merchant_id = :m and role = 'owner'")
                .param("m", merchantId)
                .query(Long.class)
                .single();
    }

    @Override
    public void addSeat(String merchantId, String userId, MerchantRole role, String invitedBy, Instant at) {
        jdbc.sql("""
                        insert into merchants.merchant_members
                               (merchant_id, user_id, role, bookable, mfa_ok, joined_at, invited_by)
                        values (:m, :u, :role, :bookable, true, :at, :by)
                        """)
                .param("m", merchantId)
                .param("u", userId)
                .param("role", role.code())
                .param("bookable", role == MerchantRole.TECHNICIAN)
                .param("at", ts(at))
                .param("by", invitedBy)
                .update();
    }

    @Override
    public void changeRole(String merchantId, String userId, MerchantRole role) {
        jdbc.sql("update merchants.merchant_members set role = :role where merchant_id = :m and user_id = :u")
                .param("m", merchantId)
                .param("u", userId)
                .param("role", role.code())
                .update();
    }

    @Override
    public void removeSeat(String merchantId, String userId) {
        jdbc.sql("delete from merchants.merchant_members where merchant_id = :m and user_id = :u")
                .param("m", merchantId)
                .param("u", userId)
                .update();
    }

    @Override
    public List<TeamInvitation> openInvitations(String merchantId) {
        return jdbc.sql(INVITATIONS + """
                         where merchant_id = :m and accepted_at is null and revoked_at is null
                         order by created_at desc, id desc
                        """)
                .param("m", merchantId)
                .query((rs, _) -> invitation(rs))
                .list();
    }

    @Override
    public Optional<TeamInvitation> invitation(String merchantId, String invitationId) {
        return jdbc.sql(INVITATIONS + " where merchant_id = :m and id = :id")
                .param("m", merchantId)
                .param("id", invitationId)
                .query((rs, _) -> invitation(rs))
                .optional();
    }

    @Override
    public Optional<TeamInvitation> invitationByTokenHash(String tokenHash) {
        return jdbc.sql(INVITATIONS + " where token_hash = :h")
                .param("h", tokenHash)
                .query((rs, _) -> invitation(rs))
                .optional();
    }

    @Override
    public boolean pendingInvitationFor(String merchantId, TeamRules.Contact contact, Instant now) {
        return jdbc.sql("""
                        select exists (select 1 from merchants.member_invitations
                                        where merchant_id = :m and accepted_at is null and revoked_at is null
                                          and expires_at > :now
                                          and (email = cast(:email as citext) or phone = :phone))
                        """)
                .param("m", merchantId)
                .param("now", ts(now))
                .param("email", contact.email())
                .param("phone", contact.phone())
                .query(Boolean.class)
                .single();
    }

    @Override
    public void revokeExpiredFor(String merchantId, TeamRules.Contact contact, Instant now) {
        jdbc.sql("""
                        update merchants.member_invitations set revoked_at = :now
                         where merchant_id = :m and accepted_at is null and revoked_at is null and expires_at <= :now
                           and (email = cast(:email as citext) or phone = :phone)
                        """)
                .param("m", merchantId)
                .param("now", ts(now))
                .param("email", contact.email())
                .param("phone", contact.phone())
                .update();
    }

    @Override
    public void insertInvitation(TeamInvitation i, String tokenHash) {
        jdbc.sql("""
                        insert into merchants.member_invitations
                               (id, merchant_id, role, email, phone, token_hash, invited_by, created_at, expires_at)
                        values (:id, :m, :role, cast(:email as citext), :phone, :hash, :by, :at, :expires)
                        """)
                .param("id", i.id())
                .param("m", i.merchantId())
                .param("role", i.role().code())
                .param("email", i.contact().email())
                .param("phone", i.contact().phone())
                .param("hash", tokenHash)
                .param("by", i.invitedBy())
                .param("at", ts(i.createdAt()))
                .param("expires", ts(i.expiresAt()))
                .update();
    }

    @Override
    public void markAccepted(String invitationId, String userId, Instant at) {
        jdbc.sql("update merchants.member_invitations set accepted_at = :at, accepted_by = :u where id = :id")
                .param("id", invitationId)
                .param("u", userId)
                .param("at", ts(at))
                .update();
    }

    @Override
    public void markRevoked(String invitationId, Instant at) {
        jdbc.sql("update merchants.member_invitations set revoked_at = :at where id = :id")
                .param("id", invitationId)
                .param("at", ts(at))
                .update();
    }

    private static final String INVITATIONS = """
            select id, merchant_id, role, email::text as email, phone, invited_by, created_at, expires_at,
                   accepted_at, revoked_at
              from merchants.member_invitations
            """;

    private static Seat seat(ResultSet rs) throws SQLException {
        return new Seat(
                rs.getString("user_id"),
                CodedEnum.fromCode(MerchantRole.class, rs.getString("role")),
                requiredInstant(rs, "joined_at"));
    }

    private static TeamInvitation invitation(ResultSet rs) throws SQLException {
        return new TeamInvitation(
                rs.getString("id"),
                rs.getString("merchant_id"),
                CodedEnum.fromCode(MerchantRole.class, rs.getString("role")),
                new TeamRules.Contact(rs.getString("email"), rs.getString("phone")),
                rs.getString("invited_by"),
                requiredInstant(rs, "created_at"),
                requiredInstant(rs, "expires_at"),
                instant(rs, "accepted_at"),
                instant(rs, "revoked_at"));
    }
}
