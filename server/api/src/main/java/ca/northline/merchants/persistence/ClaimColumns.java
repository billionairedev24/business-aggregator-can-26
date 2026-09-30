package ca.northline.merchants.persistence;

import static ca.northline.merchants.persistence.ApplicationPersistenceAdapter.instant;
import static ca.northline.merchants.persistence.ApplicationPersistenceAdapter.timestamp;

import ca.northline.merchants.domain.CustomDomain;
import ca.northline.merchants.domain.DomainClaim;
import ca.northline.merchants.domain.DomainProblem;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.CodedEnums;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/** {@link DomainClaim} ↔ the {@code custom_domain*} columns of {@code merchants.storefronts} (V004, V030, V085). */
final class ClaimColumns {
    private ClaimColumns() {}

    static final String SELECT = """
                   custom_domain, custom_domain_token, custom_domain_status, custom_domain_status_at,
                   custom_domain_verified_at, custom_domain_checked_at, custom_domain_next_check_at,
                   custom_domain_problem, custom_domain_dns_lost_at, custom_domain_live_at, custom_domain_requested_at,
                   custom_domain_failures
            """;

    static @Nullable DomainClaim read(ResultSet rs) throws SQLException {
        var domain = rs.getString("custom_domain");
        if (domain == null) {
            return null;
        }
        return new DomainClaim(
                new CustomDomain(domain),
                rs.getString("custom_domain_token"),
                CodedEnum.fromCode(CustomDomain.Status.class, rs.getString("custom_domain_status")),
                Objects.requireNonNull(instant(rs, "custom_domain_status_at")),
                instant(rs, "custom_domain_verified_at"),
                instant(rs, "custom_domain_checked_at"),
                instant(rs, "custom_domain_next_check_at"),
                CodedEnums.fromCode(rs.getString("custom_domain_problem"), DomainProblem.class),
                instant(rs, "custom_domain_dns_lost_at"),
                instant(rs, "custom_domain_live_at"),
                instant(rs, "custom_domain_requested_at"),
                rs.getInt("custom_domain_failures"));
    }

    /** Every column; all null (and no failures) when {@code claim} is null. */
    static MapSqlParameterSource params(@Nullable DomainClaim claim) {
        var p = new MapSqlParameterSource();
        p.addValue("domain", claim == null ? null : claim.domain().value());
        p.addValue("token", claim == null ? null : claim.token());
        p.addValue("status", claim == null ? null : claim.status().code());
        p.addValue("statusAt", claim == null ? null : timestamp(claim.statusSince()));
        p.addValue("verifiedAt", claim == null ? null : timestamp(claim.verifiedAt()));
        p.addValue("checkedAt", claim == null ? null : timestamp(claim.checkedAt()));
        p.addValue("nextCheckAt", claim == null ? null : timestamp(claim.nextCheckAt()));
        p.addValue("problem", claim == null ? null : CodedEnums.toCode(claim.problem()));
        p.addValue("dnsLostAt", claim == null ? null : timestamp(claim.dnsLostAt()));
        p.addValue("liveAt", claim == null ? null : timestamp(claim.liveAt()));
        p.addValue("requestedAt", claim == null ? null : timestamp(claim.requestedAt()));
        p.addValue("failures", claim == null ? 0 : claim.failures());
        return p;
    }
}
