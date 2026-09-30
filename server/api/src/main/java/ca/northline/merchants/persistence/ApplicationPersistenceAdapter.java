package ca.northline.merchants.persistence;

import ca.northline.merchants.application.ApplicationRepository;
import ca.northline.merchants.domain.BusinessProfile;
import ca.northline.merchants.domain.BusinessStructure;
import ca.northline.merchants.domain.CategoryRoot;
import ca.northline.merchants.domain.MerchantApplication;
import ca.northline.merchants.domain.MerchantStatus;
import ca.northline.merchants.domain.MerchantTier;
import ca.northline.merchants.domain.MerchantType;
import ca.northline.merchants.domain.OnboardingStep;
import ca.northline.merchants.domain.Principal;
import ca.northline.merchants.domain.PrincipalRole;
import ca.northline.merchants.domain.Province;
import ca.northline.merchants.domain.SelectedCategory;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.CodedEnums;
import ca.northline.shared.Ids;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link MerchantApplication} ↔ {@code merchants.merchants} + {@code merchant_principals} + {@code merchant_categories}.
 * Hand-written SQL: the aggregate spans three tables and several jsonb/array columns.
 */
@Repository
@RequiredArgsConstructor
class ApplicationPersistenceAdapter implements ApplicationRepository {

    private final JdbcClient jdbc;
    private final MerchantJsonColumns json;

    @Override
    public Optional<MerchantApplication> findById(String merchantId) {
        return jdbc.sql("""
                        select id, type, status, onboarding_step, province, work_email, business_terms_accepted_at,
                               created_by, tier, take_rate_bps, display_name, legal_name, structure, gst_number,
                               legal_details::text as legal_details, profile::text as profile, city, languages,
                               business_number, registry_ref, registry_jurisdiction, submitted_at, approved_at,
                               created_at, updated_at
                          from merchants.merchants where id = :id
                        """)
                .param("id", merchantId)
                .query((rs, _) -> toDomain(rs))
                .optional();
    }

    private MerchantApplication toDomain(ResultSet rs) throws SQLException {
        var id = rs.getString("id");
        var status = Objects.requireNonNullElse(
                CodedEnums.fromCode(rs.getString("status"), MerchantStatus.class), MerchantStatus.APPLICANT);
        var step = CodedEnums.fromCode(rs.getString("onboarding_step"), OnboardingStep.class);
        return MerchantApplication.builder()
                .id(id)
                .type(CodedEnum.fromCode(MerchantType.class, rs.getString("type")))
                .status(status)
                .step(
                        step != null
                                ? step
                                : status == MerchantStatus.APPLICANT ? OnboardingStep.BUSINESS : OnboardingStep.DONE)
                .province(CodedEnums.fromCode(rs.getString("province"), Province.class))
                .workEmail(rs.getString("work_email"))
                .businessTermsAcceptedAt(instant(rs, "business_terms_accepted_at"))
                .createdBy(Objects.requireNonNullElse(rs.getString("created_by"), ""))
                .tier(CodedEnums.fromCode(rs.getString("tier"), MerchantTier.class))
                .takeRateBps(rs.getInt("take_rate_bps"))
                .displayName(rs.getString("display_name"))
                .legalName(rs.getString("legal_name"))
                .structure(CodedEnums.fromCode(rs.getString("structure"), BusinessStructure.class))
                .gstNumber(rs.getString("gst_number"))
                .legalDetails(json.map(rs.getString("legal_details")))
                .principals(principals(id))
                .categories(categories(id))
                .profile(json.read(rs.getString("profile"), BusinessProfile.class, BusinessProfile.EMPTY))
                .city(rs.getString("city"))
                .languages(strings(rs.getArray("languages")))
                .businessNumber(rs.getString("business_number"))
                .registryRef(rs.getString("registry_ref"))
                .registryJurisdiction(rs.getString("registry_jurisdiction"))
                .submittedAt(instant(rs, "submitted_at"))
                .approvedAt(instant(rs, "approved_at"))
                .createdAt(Objects.requireNonNull(instant(rs, "created_at")))
                .updatedAt(Objects.requireNonNull(instant(rs, "updated_at")))
                .build();
    }

    private List<Principal> principals(String merchantId) {
        return jdbc.sql("""
                        select legal_name, role, ownership_pct from merchants.merchant_principals
                         where merchant_id = :id order by ownership_pct desc nulls last, legal_name
                        """)
                .param("id", merchantId)
                .query((rs, _) -> new Principal(
                        rs.getString("legal_name"),
                        CodedEnum.fromCode(PrincipalRole.class, rs.getString("role")),
                        rs.getBigDecimal("ownership_pct")))
                .list();
    }

    private List<SelectedCategory> categories(String merchantId) {
        return jdbc.sql("""
                        select mc.category_id, mc.suggested_name, c.root, c.name_i18n::text as names, c.regulated_registry
                          from merchants.merchant_categories mc
                          left join catalogue.categories c on c.id = mc.category_id
                         where mc.merchant_id = :id
                         order by mc.suggested_name nulls first, mc.category_id
                        """)
                .param("id", merchantId)
                .query((rs, _) -> {
                    var suggested = rs.getString("suggested_name");
                    var names = json.map(rs.getString("names"));
                    var name = suggested != null
                            ? suggested
                            : String.valueOf(names.getOrDefault("en", rs.getString("category_id")));
                    return new SelectedCategory(
                            rs.getString("category_id"),
                            name,
                            CodedEnums.fromCode(rs.getString("root"), CategoryRoot.class),
                            rs.getString("regulated_registry"),
                            suggested != null);
                })
                .list();
    }

    @Override
    public void insert(MerchantApplication a, String ownerId) {
        jdbc.sql("""
                        insert into merchants.merchants (id, type, display_name, legal_name, tier, take_rate_bps, status,
                               province, work_email, profile, onboarding_step, business_terms_accepted_at, created_by,
                               languages, created_at, updated_at)
                        values (:id, :type, :displayName, :legalName, :tier, :takeRate, :status, :province, :workEmail,
                                cast(:profile as jsonb), :step, :termsAt, :createdBy, cast(:languages as text[]),
                                :createdAt, :updatedAt)
                        """)
                .param("id", a.getId())
                .param("type", a.getType().code())
                .param("displayName", a.getDisplayName())
                .param("legalName", a.getLegalName())
                .param("tier", CodedEnums.toCode(a.getTier()))
                .param("takeRate", a.getTakeRateBps())
                .param("status", a.getStatus().code())
                .param("province", CodedEnums.toCode(a.getProvince()))
                .param("workEmail", a.getWorkEmail())
                .param("profile", json.write(a.getProfile()))
                .param("step", a.getStep().code())
                .param("termsAt", timestamp(a.getBusinessTermsAcceptedAt()))
                .param("createdBy", a.getCreatedBy())
                .param("languages", pgArray(a.getLanguages()))
                .param("createdAt", timestamp(a.getCreatedAt()))
                .param("updatedAt", timestamp(a.getUpdatedAt()))
                .update();
        jdbc.sql("""
                        insert into merchants.merchant_members (merchant_id, user_id, role, bookable, mfa_ok)
                        values (:m, :u, 'owner', false, true)
                        """).param("m", a.getId()).param("u", ownerId).update();
    }

    @Override
    public void save(MerchantApplication a) {
        jdbc.sql("""
                        update merchants.merchants set type = :type, status = :status, display_name = :displayName,
                               legal_name = :legalName, structure = :structure, gst_number = :gst,
                               legal_details = cast(:legal as jsonb), profile = cast(:profile as jsonb), city = :city,
                               languages = cast(:languages as text[]), business_number = :bn, registry_ref = :registryRef,
                               registry_jurisdiction = :jurisdiction, province = :province, work_email = :workEmail,
                               onboarding_step = :step, business_terms_accepted_at = :termsAt,
                               submitted_at = :submittedAt, approved_at = :approvedAt, updated_at = :updatedAt
                         where id = :id
                        """)
                .param("id", a.getId())
                .param("type", a.getType().code())
                .param("status", a.getStatus().code())
                .param("displayName", a.getDisplayName())
                .param("legalName", a.getLegalName())
                .param("structure", CodedEnums.toCode(a.getStructure()))
                .param("gst", a.getGstNumber())
                .param("legal", a.getLegalDetails().isEmpty() ? null : json.write(a.getLegalDetails()))
                .param("profile", json.write(a.getProfile()))
                .param("city", a.getCity())
                .param("languages", pgArray(a.getLanguages()))
                .param("bn", a.getBusinessNumber())
                .param("registryRef", a.getRegistryRef())
                .param("jurisdiction", a.getRegistryJurisdiction())
                .param("province", CodedEnums.toCode(a.getProvince()))
                .param("workEmail", a.getWorkEmail())
                .param("step", a.getStep().code())
                .param("termsAt", timestamp(a.getBusinessTermsAcceptedAt()))
                .param("submittedAt", timestamp(a.getSubmittedAt()))
                .param("approvedAt", timestamp(a.getApprovedAt()))
                .param("updatedAt", timestamp(a.getUpdatedAt()))
                .update();
        replacePrincipals(a);
        replaceCategories(a);
    }

    /**
     * Makes the stored principals equal to the application's. A principal whose legal name is unchanged (ignoring case
     * and spacing) keeps its row id, its "this is me" user and its identity check (S-22); the rest are replaced.
     */
    private void replacePrincipals(MerchantApplication a) {
        var existing = new java.util.HashMap<String, String>();
        jdbc.sql("select id, legal_name from merchants.merchant_principals where merchant_id = :id order by id")
                .param("id", a.getId())
                .query((rs, _) -> existing.putIfAbsent(nameKey(rs.getString("legal_name")), rs.getString("id")))
                .list();
        for (var p : a.getPrincipals()) {
            var id = existing.remove(nameKey(p.legalName()));
            if (id != null) {
                jdbc.sql("""
                                update merchants.merchant_principals set legal_name = :name, role = :role, ownership_pct = :pct
                                 where id = :id
                                """)
                        .param("id", id)
                        .param("name", p.legalName())
                        .param("role", p.role().code())
                        .param("pct", p.ownershipPct())
                        .update();
                continue;
            }
            jdbc.sql("""
                            insert into merchants.merchant_principals (id, merchant_id, legal_name, role, ownership_pct)
                            values (:id, :m, :name, :role, :pct)
                            """)
                    .param("id", Ids.next())
                    .param("m", a.getId())
                    .param("name", p.legalName())
                    .param("role", p.role().code())
                    .param("pct", p.ownershipPct())
                    .update();
        }
        if (!existing.isEmpty()) {
            jdbc.sql("delete from merchants.merchant_principals where merchant_id = :m and id in (:ids)")
                    .param("m", a.getId())
                    .param("ids", List.copyOf(existing.values()))
                    .update();
        }
    }

    private static String nameKey(String legalName) {
        return legalName.strip().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    private void replaceCategories(MerchantApplication a) {
        jdbc.sql("delete from merchants.merchant_categories where merchant_id = :id")
                .param("id", a.getId())
                .update();
        for (var c : a.getCategories()) {
            jdbc.sql("""
                            insert into merchants.merchant_categories (merchant_id, category_id, status, suggested_name)
                            values (:m, :c, :status, :suggested)
                            """)
                    .param("m", a.getId())
                    .param("c", c.id())
                    .param("status", c.initialStatus().code())
                    .param("suggested", c.suggested() ? c.name() : null)
                    .update();
        }
    }

    @Override
    public void linkPrincipalKyc(String merchantId, String kycVerificationId, int thresholdPct) {
        jdbc.sql("""
                        update merchants.merchant_principals
                           set kyc_verification_id = case when :t = 0 or ownership_pct >= :t then :kyc end
                         where merchant_id = :m
                        """)
                .param("t", thresholdPct)
                .param("kyc", kycVerificationId)
                .param("m", merchantId)
                .update();
    }

    @Override
    public boolean businessNumberTaken(String businessNumber, String exceptMerchantId) {
        return jdbc.sql("select exists(select 1 from merchants.merchants where business_number = :bn and id <> :id)")
                .param("bn", businessNumber)
                .param("id", exceptMerchantId)
                .query(Boolean.class)
                .single();
    }

    static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        var ts = rs.getObject(column, OffsetDateTime.class);
        return ts == null ? null : ts.toInstant();
    }

    /** timestamptz parameter. */
    static @Nullable OffsetDateTime timestamp(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static List<String> strings(@Nullable Array array) throws SQLException {
        return array == null
                ? List.of()
                : Arrays.stream((Object[]) array.getArray())
                        .map(String::valueOf)
                        .toList();
    }

    /** Postgres array literal; language codes are plain ASCII letters. */
    private static String pgArray(List<String> values) {
        return values.stream()
                .filter(v -> v.matches("[a-z]{2,3}"))
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }
}
