package ca.northline.merchants.persistence;

import static ca.northline.shared.JdbcTimes.instant;
import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.merchants.application.OwnerIdentityStore;
import ca.northline.merchants.domain.IdentityCheckStatus;
import ca.northline.merchants.domain.IdentityMatch;
import ca.northline.merchants.domain.OwnerIdentityCheck;
import ca.northline.merchants.domain.PrincipalRole;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.CodedEnums;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link OwnerIdentityStore} over {@code merchant_principals} (KYC-linked) + {@code owner_identity_checks} (V032). */
@Repository
@RequiredArgsConstructor
class OwnerIdentityQueries implements OwnerIdentityStore {

    private static final String CHECK_COLUMNS = """
            c.id as c_id, c.merchant_id as c_merchant, c.principal_id as c_principal, c.stripe_session, c.status,
            c.last_error, c.name_match, c.dob_match, c.delivery, c.email, c.attempts, c.requested_by,
            c.stripe_updated_at, c.verified_at, c.updated_at, c.reviewed_by, c.reviewed_at, c.review_note
            """;

    private final JdbcClient jdbc;

    @Override
    public List<Owner> owners(String merchantId) {
        return jdbc.sql("select p.id as p_id, p.legal_name, p.role, p.ownership_pct, p.user_id, " + CHECK_COLUMNS + """
                          from merchants.merchant_principals p
                          left join merchants.owner_identity_checks c
                                 on c.merchant_id = p.merchant_id and c.principal_id = p.id
                         where p.merchant_id = :m and p.kyc_verification_id is not null
                         order by p.ownership_pct desc nulls last, p.legal_name, p.id
                        """)
                .param("m", merchantId)
                .query((rs, _) -> new Owner(
                        rs.getString("p_id"),
                        rs.getString("legal_name"),
                        CodedEnum.fromCode(PrincipalRole.class, rs.getString("role")),
                        rs.getBigDecimal("ownership_pct"),
                        rs.getString("user_id"),
                        rs.getString("c_id") == null ? null : check(rs)))
                .list();
    }

    @Override
    public Optional<OwnerIdentityCheck> lockBySession(String sessionId) {
        return jdbc.sql("select " + CHECK_COLUMNS
                        + " from merchants.owner_identity_checks c where c.stripe_session = :s for update")
                .param("s", sessionId)
                .query((rs, _) -> check(rs))
                .optional();
    }

    @Override
    public Optional<OwnerIdentityCheck> find(String merchantId, String checkId) {
        return jdbc.sql("select " + CHECK_COLUMNS
                        + " from merchants.owner_identity_checks c where c.merchant_id = :m and c.id = :id")
                .param("m", merchantId)
                .param("id", checkId)
                .query((rs, _) -> check(rs))
                .optional();
    }

    @Override
    public void save(OwnerIdentityCheck c) {
        jdbc.sql("""
                        insert into merchants.owner_identity_checks (id, merchant_id, principal_id, stripe_session,
                               status, last_error, name_match, dob_match, delivery, email, attempts, requested_by,
                               stripe_updated_at, verified_at, updated_at, reviewed_by, reviewed_at, review_note)
                        values (:id, :m, :p, :session, :status, :error, :name, :dob, :delivery, :email, :attempts, :by,
                                :stripeAt, :verifiedAt, :at, :reviewedBy, :reviewedAt, :reviewNote)
                        on conflict (id) do update set stripe_session = excluded.stripe_session,
                               status = excluded.status, last_error = excluded.last_error,
                               name_match = excluded.name_match, dob_match = excluded.dob_match,
                               delivery = excluded.delivery, email = excluded.email, attempts = excluded.attempts,
                               requested_by = excluded.requested_by, stripe_updated_at = excluded.stripe_updated_at,
                               verified_at = excluded.verified_at, updated_at = excluded.updated_at,
                               reviewed_by = excluded.reviewed_by, reviewed_at = excluded.reviewed_at,
                               review_note = excluded.review_note
                        """)
                .param("id", c.getId())
                .param("m", c.getMerchantId())
                .param("p", c.getPrincipalId())
                .param("session", c.getStripeSession())
                .param("status", c.getStatus().code())
                .param("error", c.getLastError())
                .param("name", CodedEnums.toCode(c.getNameMatch()))
                .param("dob", CodedEnums.toCode(c.getDobMatch()))
                .param("delivery", c.getDelivery().code())
                .param("email", c.getEmail())
                .param("attempts", c.getAttempts())
                .param("by", c.getRequestedBy())
                .param("stripeAt", ts(c.getStripeUpdatedAt()))
                .param("verifiedAt", ts(c.getVerifiedAt()))
                .param("at", ts(c.getUpdatedAt()))
                .param("reviewedBy", c.getReviewedBy())
                .param("reviewedAt", ts(c.getReviewedAt()))
                .param("reviewNote", c.getReviewNote())
                .update();
    }

    @Override
    public void bindUser(String merchantId, String principalId, String userId) {
        jdbc.sql("update merchants.merchant_principals set user_id = :u where merchant_id = :m and id = :p")
                .param("u", userId)
                .param("m", merchantId)
                .param("p", principalId)
                .update();
    }

    @Override
    public Optional<String> stripeAccount(String merchantId) {
        return jdbc.sql(
                        "select stripe_account_id from merchants.merchants where id = :m and stripe_account_id is not null")
                .param("m", merchantId)
                .query(String.class)
                .optional();
    }

    private static OwnerIdentityCheck check(ResultSet rs) throws SQLException {
        return OwnerIdentityCheck.builder()
                .id(rs.getString("c_id"))
                .merchantId(rs.getString("c_merchant"))
                .principalId(rs.getString("c_principal"))
                .stripeSession(rs.getString("stripe_session"))
                .status(CodedEnum.fromCode(IdentityCheckStatus.class, rs.getString("status")))
                .lastError(rs.getString("last_error"))
                .nameMatch(match(rs.getString("name_match")))
                .dobMatch(match(rs.getString("dob_match")))
                .delivery(CodedEnum.fromCode(OwnerIdentityCheck.Delivery.class, rs.getString("delivery")))
                .email(rs.getString("email"))
                .attempts(rs.getInt("attempts"))
                .requestedBy(rs.getString("requested_by"))
                .stripeUpdatedAt(instant(rs, "stripe_updated_at"))
                .verifiedAt(instant(rs, "verified_at"))
                .updatedAt(requiredInstant(rs, "updated_at"))
                .reviewedBy(rs.getString("reviewed_by"))
                .reviewedAt(instant(rs, "reviewed_at"))
                .reviewNote(rs.getString("review_note"))
                .build();
    }

    private static @Nullable IdentityMatch match(@Nullable String code) {
        return CodedEnums.fromCode(code, IdentityMatch.class);
    }
}
