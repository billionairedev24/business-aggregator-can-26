package ca.northline.merchants.persistence;

import ca.northline.merchants.api.TeamRoster;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.NotFound;
import ca.northline.shared.security.MerchantRole;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link TeamRoster} for the operations screens (Availability). Added by the operations workstream; the
 * {@code ComplianceStatus} part moved to the compliance ledger (settings &amp; compliance workstream).
 */
@Repository
@RequiredArgsConstructor
class OperationsQueries implements TeamRoster {

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
}
