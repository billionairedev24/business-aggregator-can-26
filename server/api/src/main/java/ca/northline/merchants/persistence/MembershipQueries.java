package ca.northline.merchants.persistence;

import ca.northline.merchants.application.BusinessDirectory;
import ca.northline.merchants.application.BusinessSummary;
import ca.northline.merchants.domain.MerchantTier;
import ca.northline.merchants.domain.MerchantType;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.CodedEnums;
import ca.northline.shared.security.MerchantMemberships;
import ca.northline.shared.security.MerchantRole;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Membership reads over {@code merchants.merchant_members}. A join read model, so plain SQL via {@link JdbcClient}
 * (Spring Data derived queries don't cover it). Implements both the shared security port and this module's
 * directory port.
 */
@Repository
@RequiredArgsConstructor
class MembershipQueries implements MerchantMemberships, BusinessDirectory {

    private final JdbcClient jdbc;

    @Override
    public Optional<MerchantRole> roleOf(String merchantId, String userId) {
        return jdbc.sql(
                        "select role from merchants.merchant_members where merchant_id = :merchantId and user_id = :userId")
                .param("merchantId", merchantId)
                .param("userId", userId)
                .query(String.class)
                .optional()
                .map(code -> CodedEnum.fromCode(MerchantRole.class, code));
    }

    @Override
    public List<Membership> membershipsOf(String userId) {
        return jdbc.sql(
                        "select merchant_id, role from merchants.merchant_members where user_id = :userId order by merchant_id")
                .param("userId", userId)
                .query((rs, _) -> new Membership(
                        rs.getString("merchant_id"), CodedEnum.fromCode(MerchantRole.class, rs.getString("role"))))
                .list();
    }

    @Override
    public List<BusinessSummary> businessesOf(String userId) {
        return jdbc.sql("""
                        select m.id, m.display_name, m.type, m.tier, m.city, mm.role
                          from merchants.merchant_members mm
                          join merchants.merchants m on m.id = mm.merchant_id
                         where mm.user_id = :userId
                         order by m.created_at, m.id
                        """)
                .param("userId", userId)
                .query((rs, _) -> new BusinessSummary(
                        rs.getString("id"),
                        rs.getString("display_name"),
                        CodedEnum.fromCode(MerchantType.class, rs.getString("type")),
                        CodedEnums.fromCode(rs.getString("tier"), MerchantTier.class),
                        rs.getString("city"),
                        CodedEnum.fromCode(MerchantRole.class, rs.getString("role"))))
                .list();
    }
}
