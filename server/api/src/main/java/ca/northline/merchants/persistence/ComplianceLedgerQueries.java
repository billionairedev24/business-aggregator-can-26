package ca.northline.merchants.persistence;

import static ca.northline.shared.JdbcTimes.instant;
import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.merchants.application.ComplianceLedgerStore;
import ca.northline.merchants.domain.CheckType;
import ca.northline.merchants.domain.ComplianceItem;
import ca.northline.merchants.domain.MerchantType;
import ca.northline.merchants.domain.VerificationStatus;
import ca.northline.shared.CodedEnum;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link ComplianceLedgerStore} over {@code merchants.verifications} (+ principals, categories and
 * {@code obligation_acceptances}). The category name is a logical read of {@code catalogue.categories} (reference data,
 * as onboarding does). Rows added without a checklist key that duplicate a keyed row (same type, registry and
 * reference) are hidden, so a licence recorded twice shows once.
 */
@Repository
@RequiredArgsConstructor
class ComplianceLedgerQueries implements ComplianceLedgerStore {

    private static final String LEDGER = """
            select v.id, v.check_type, v.check_key, v.registry, v.reference, v.label, v.status, v.expires_at,
                   v.verified_at, v.submitted_at
              from merchants.verifications v
             where v.merchant_id = :m
               and v.check_type not in ('kyc', 'bank', 'mfa', 'site_visit')
               and coalesce(v.check_key, '') <> 'registry'
               and not (v.check_key is null and exists (
                        select 1 from merchants.verifications k
                         where k.merchant_id = v.merchant_id and k.check_key is not null
                           and k.check_type = v.check_type
                           and k.registry is not distinct from v.registry
                           and k.reference is not distinct from v.reference))
            """;

    private final JdbcClient jdbc;
    private final OnboardingTaxonomyQueries.SeedOrder seed = OnboardingTaxonomyQueries.SeedOrder.load();

    @Override
    public List<ComplianceItem> items(String merchantId, Instant now) {
        return jdbc.sql(LEDGER + " order by (v.check_key is null), v.position nulls last, v.created_at, v.id")
                .param("m", merchantId)
                .query((rs, _) -> item(rs, now))
                .list();
    }

    @Override
    public Optional<ComplianceItem> item(String merchantId, String verificationId, Instant now) {
        return jdbc.sql(LEDGER + " and v.id = :id")
                .param("m", merchantId)
                .param("id", verificationId)
                .query((rs, _) -> item(rs, now))
                .optional();
    }

    @Override
    public void submitRenewal(String verificationId, String documentId, String actorId, Instant at) {
        jdbc.sql("""
                        update merchants.verifications
                           set status = 'submitted', document_media_id = :doc, submitted_at = :at, submitted_by = :by,
                               updated_at = :at
                         where id = :id
                        """)
                .param("id", verificationId)
                .param("doc", documentId)
                .param("by", actorId)
                .param("at", ts(at))
                .update();
    }

    @Override
    public Optional<BusinessFacts> facts(String merchantId) {
        return jdbc.sql("""
                        select m.type, m.display_name, m.legal_name, m.business_number, m.province, m.take_rate_bps,
                               m.stripe_account_id, m.status,
                               (select p.legal_name from merchants.merchant_principals p
                                 where p.merchant_id = m.id
                                 order by p.ownership_pct desc nulls last, p.id limit 1) as owner_name
                          from merchants.merchants m where m.id = :m
                        """)
                .param("m", merchantId)
                .query((rs, _) -> new BusinessFacts(
                        CodedEnum.fromCode(MerchantType.class, rs.getString("type")),
                        rs.getString("display_name"),
                        rs.getString("legal_name"),
                        rs.getString("business_number"),
                        rs.getString("province"),
                        requiredFor(merchantId),
                        rs.getString("owner_name"),
                        rs.getObject("take_rate_bps") == null ? null : rs.getInt("take_rate_bps"),
                        rs.getString("stripe_account_id"),
                        rs.getString("status")))
                .optional();
    }

    @Override
    public void linkStripeAccount(String merchantId, String accountId) {
        jdbc.sql("update merchants.merchants set stripe_account_id = :acct where id = :m and stripe_account_id is null")
                .param("m", merchantId)
                .param("acct", accountId)
                .update();
    }

    @Override
    public Optional<Acceptance> latestAcceptance(String merchantId) {
        return jdbc.sql("""
                        select version, accepted_at from merchants.obligation_acceptances where merchant_id = :m
                         order by accepted_at desc, version desc limit 1
                        """)
                .param("m", merchantId)
                .query((rs, _) -> new Acceptance(rs.getString("version"), requiredInstant(rs, "accepted_at")))
                .optional();
    }

    @Override
    public void accept(String merchantId, String version, String actorId, Instant at) {
        jdbc.sql("""
                        insert into merchants.obligation_acceptances (merchant_id, version, accepted_by, accepted_at)
                        values (:m, :v, :by, :at) on conflict (merchant_id, version) do nothing
                        """)
                .param("m", merchantId)
                .param("v", version)
                .param("by", actorId)
                .param("at", ts(at))
                .update();
    }

    /** The approved category the licences are for: regulated ones first, then in taxonomy order ("Mobile mechanic"). */
    private @Nullable String requiredFor(String merchantId) {
        record Category(String id, @Nullable String name, boolean regulated) {}
        return jdbc
                .sql("""
                        select mc.category_id, coalesce(c.name_i18n ->> 'en', mc.suggested_name) as name,
                               c.regulated_registry is not null as regulated
                          from merchants.merchant_categories mc
                          left join catalogue.categories c on c.id = mc.category_id
                         where mc.merchant_id = :m and mc.status = 'approved'
                        """)
                .param("m", merchantId)
                .query((rs, _) ->
                        new Category(rs.getString("category_id"), rs.getString("name"), rs.getBoolean("regulated")))
                .list()
                .stream()
                .filter(c -> c.name() != null)
                .sorted(Comparator.comparing((Category c) -> !c.regulated())
                        .thenComparingInt(c -> seed.ordinal(c.id())))
                .map(Category::name)
                .findFirst()
                .orElse(null);
    }

    private static ComplianceItem item(ResultSet rs, Instant now) throws SQLException {
        return ComplianceItem.of(
                rs.getString("id"),
                CodedEnum.fromCode(CheckType.class, rs.getString("check_type")),
                rs.getString("check_key"),
                rs.getString("registry"),
                rs.getString("reference"),
                rs.getString("label"),
                CodedEnum.fromCode(VerificationStatus.class, rs.getString("status")),
                instant(rs, "expires_at"),
                instant(rs, "verified_at"),
                instant(rs, "submitted_at"),
                now);
    }
}
