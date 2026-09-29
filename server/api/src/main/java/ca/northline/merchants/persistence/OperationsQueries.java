package ca.northline.merchants.persistence;

import ca.northline.merchants.api.ComplianceStatus;
import ca.northline.merchants.api.TeamRoster;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.JdbcTimes;
import ca.northline.shared.NotFound;
import ca.northline.shared.security.MerchantRole;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link TeamRoster} and {@link ComplianceStatus} for the operations screens (Availability, Dashboard). Added by the
 * operations workstream; the onboarding and compliance workstreams own these tables and may replace this adapter.
 */
@Repository
@RequiredArgsConstructor
class OperationsQueries implements TeamRoster, ComplianceStatus {

    private final JdbcClient jdbc;

    @Override
    public List<TeamMember> members(String merchantId) {
        return jdbc.sql("""
                        select user_id, role, coalesce(bookable, false) as bookable
                          from merchants.merchant_members where merchant_id = :merchantId
                         order by (role = 'owner') desc, user_id
                        """)
                .param("merchantId", merchantId)
                .query((rs, _) -> new TeamMember(
                        rs.getString("user_id"),
                        CodedEnum.fromCode(MerchantRole.class, rs.getString("role")),
                        rs.getBoolean("bookable")))
                .list();
    }

    @Override
    public void setBookable(String merchantId, String userId, boolean bookable) {
        var updated = jdbc.sql("""
                        update merchants.merchant_members set bookable = :bookable
                         where merchant_id = :merchantId and user_id = :userId
                        """)
                .param("bookable", bookable)
                .param("merchantId", merchantId)
                .param("userId", userId)
                .update();
        if (updated == 0) {
            throw new NotFound("team member", userId);
        }
    }

    @Override
    public List<DueItem> dueItems(String merchantId) {
        return jdbc.sql("""
                        select check_type, registry, status, expires_at from merchants.verifications
                         where merchant_id = :merchantId and status in ('expired', 'todo')
                         order by expires_at nulls last, check_type
                        """)
                .param("merchantId", merchantId)
                .query((rs, _) -> {
                    var expires = JdbcTimes.instant(rs, "expires_at");
                    return new DueItem(
                            rs.getString("check_type"), rs.getString("registry"), rs.getString("status"), expires);
                })
                .list();
    }
}
