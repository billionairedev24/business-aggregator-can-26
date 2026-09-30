package ca.northline.merchants.persistence;

import static ca.northline.merchants.persistence.ApplicationPersistenceAdapter.instant;
import static ca.northline.merchants.persistence.ApplicationPersistenceAdapter.timestamp;

import ca.northline.merchants.application.StorefrontRepository;
import ca.northline.merchants.domain.BrandColor;
import ca.northline.merchants.domain.CtaLabel;
import ca.northline.merchants.domain.CustomDomain;
import ca.northline.merchants.domain.DomainClaim;
import ca.northline.merchants.domain.PageKind;
import ca.northline.merchants.domain.SectionKind;
import ca.northline.merchants.domain.Slug;
import ca.northline.merchants.domain.Storefront;
import ca.northline.merchants.domain.StorefrontSection;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.CodedEnums;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link Storefront} ↔ {@code merchants.storefronts} + {@code storefront_sections}. Reordering rewrites positions in
 * two passes (all negative first) so the non-deferrable {@code ux_section_position} unique index never sees a
 * duplicate mid-update; every section write goes through the V016 kind trigger and always-on check.
 */
@Repository
@RequiredArgsConstructor
class StorefrontPersistenceAdapter implements StorefrontRepository {

    private static final String COLUMNS = """
            select id, merchant_id, slug, page_kind, brand_color, logo_media_id, tagline_i18n::text as tagline,
                   cta_label, announcement_i18n::text as announcement, published_at, created_at, updated_at,
            """ + ClaimColumns.SELECT + """
              from merchants.storefronts
            """;

    private final JdbcClient jdbc;
    private final MerchantJsonColumns json;

    @Override
    public Optional<Storefront> findByMerchant(String merchantId) {
        return jdbc.sql(COLUMNS + " where merchant_id = :m")
                .param("m", merchantId)
                .query((rs, _) -> toDomain(rs))
                .optional();
    }

    @Override
    public Optional<Storefront> lockByMerchant(String merchantId) {
        return jdbc.sql(COLUMNS + " where merchant_id = :m for update")
                .param("m", merchantId)
                .query((rs, _) -> toDomain(rs))
                .optional();
    }

    @Override
    public Optional<Storefront> findLiveByDomain(String host) {
        return jdbc.sql(COLUMNS + " where custom_domain = :d and custom_domain_status = 'live'")
                .param("d", host)
                .query((rs, _) -> toDomain(rs))
                .optional();
    }

    @Override
    public Optional<Storefront> findBySlug(String slug) {
        return jdbc.sql(COLUMNS + " where slug = :s")
                .param("s", slug)
                .query((rs, _) -> toDomain(rs))
                .optional();
    }

    @Override
    public void insert(Storefront s) {
        jdbc.sql("""
                        insert into merchants.storefronts (id, merchant_id, slug, page_kind, brand_color, logo_media_id,
                               tagline_i18n, cta_label, announcement_i18n, published_at, created_at, updated_at)
                        values (:id, :m, :slug, :kind, :color, :logo, cast(:tagline as jsonb), :cta,
                                cast(:announcement as jsonb), :publishedAt, :createdAt, :updatedAt)
                        """).paramSource(params(s)).update();
        insertSections(s);
        if (s.getDomainClaim() != null) {
            saveClaim(s.getId(), null, s.getDomainClaim());
        }
    }

    @Override
    public void save(Storefront s) {
        jdbc.sql("""
                        update merchants.storefronts set slug = :slug, page_kind = :kind, brand_color = :color,
                               logo_media_id = :logo, tagline_i18n = cast(:tagline as jsonb), cta_label = :cta,
                               announcement_i18n = cast(:announcement as jsonb), published_at = :publishedAt,
                               updated_at = :updatedAt
                         where id = :id
                        """).paramSource(params(s)).update();
        jdbc.sql("update merchants.storefront_sections set position = -1 - position where storefront_id = :id")
                .param("id", s.getId())
                .update();
        for (var section : s.getSections()) {
            jdbc.sql("""
                            update merchants.storefront_sections
                               set position = :position, enabled = :enabled, settings = cast(:settings as jsonb),
                                   updated_at = :updatedAt
                             where id = :id
                            """)
                    .param("id", section.getId())
                    .param("position", section.getPosition())
                    .param("enabled", section.isEnabled())
                    .param("settings", json.write(section.getSettings()))
                    .param("updatedAt", timestamp(section.getUpdatedAt()))
                    .update();
        }
    }

    @Override
    public void replaceSections(Storefront s) {
        jdbc.sql("delete from merchants.storefront_sections where storefront_id = :id")
                .param("id", s.getId())
                .update();
        jdbc.sql("update merchants.storefronts set page_kind = :kind where id = :id")
                .param("kind", s.getPageKind().code())
                .param("id", s.getId())
                .update();
        insertSections(s);
    }

    @Override
    public boolean slugTaken(Slug slug, String exceptStorefrontId) {
        return jdbc.sql("select exists(select 1 from merchants.storefronts where slug = :s and id <> :id)")
                .param("s", slug.value())
                .param("id", exceptStorefrontId)
                .query(Boolean.class)
                .single();
    }

    // ── custom domains (S-31) ──────────────────────────────────────────────────────────────────────────────────

    private static final String CLAIMS = """
            select s.id, s.merchant_id, s.published_at is not null as published, m.status = 'active' as merchant_active,
            """ + ClaimColumns.SELECT + """
              from merchants.storefronts s join merchants.merchants m on m.id = s.merchant_id
            """;

    @Override
    public Optional<ClaimRow> lockClaimOf(CustomDomain domain, String exceptStorefrontId) {
        return jdbc.sql(CLAIMS + " where s.custom_domain = :d and s.id <> :id for update of s")
                .param("d", domain.value())
                .param("id", exceptStorefrontId)
                .query((rs, _) -> claimRow(rs))
                .optional();
    }

    @Override
    public List<ClaimRow> lockDueClaims(Instant now, int limit) {
        return jdbc.sql(CLAIMS + """
                         where s.custom_domain is not null and s.custom_domain_next_check_at <= :now
                         order by s.custom_domain_next_check_at limit :limit
                           for update of s skip locked
                        """)
                .param("now", timestamp(now))
                .param("limit", limit)
                .query((rs, _) -> claimRow(rs))
                .list();
    }

    @Override
    public List<ClaimRow> lockProvenClaims() {
        return jdbc.sql(CLAIMS + """
                         where s.custom_domain_status in ('verified', 'issuing', 'live')
                         order by s.custom_domain_status_at, s.id
                           for update of s
                        """).query((rs, _) -> claimRow(rs)).list();
    }

    @Override
    public boolean saveClaim(String storefrontId, @Nullable String expectedToken, @Nullable DomainClaim claim) {
        var sql = """
                update merchants.storefronts
                   set custom_domain = :domain, custom_domain_token = :token, custom_domain_status = :status,
                       custom_domain_status_at = :statusAt, custom_domain_verified_at = :verifiedAt,
                       custom_domain_checked_at = :checkedAt, custom_domain_next_check_at = :nextCheckAt,
                       custom_domain_problem = :problem, custom_domain_dns_lost_at = :dnsLostAt,
                       custom_domain_live_at = :liveAt, custom_domain_requested_at = :requestedAt,
                       custom_domain_failures = :failures
                 where id = :id
                """ + (expectedToken == null ? "" : " and custom_domain_token = :expected");
        return jdbc.sql(sql)
                        .paramSource(ClaimColumns.params(claim)
                                .addValue("id", storefrontId)
                                .addValue("expected", expectedToken))
                        .update()
                == 1;
    }

    @Override
    public int certificateRequestsSince(Instant since) {
        return jdbc.sql("select count(*) from merchants.storefronts where custom_domain_requested_at >= :since")
                .param("since", timestamp(since))
                .query(Integer.class)
                .single();
    }

    @Override
    public boolean tryEdgeLock() {
        return jdbc.sql("select pg_try_advisory_xact_lock(hashtext('northline.custom-domains.edge'))")
                .query(Boolean.class)
                .single();
    }

    private ClaimRow claimRow(ResultSet rs) throws SQLException {
        return new ClaimRow(
                rs.getString("id"),
                rs.getString("merchant_id"),
                Objects.requireNonNull(ClaimColumns.read(rs)),
                rs.getBoolean("published"),
                rs.getBoolean("merchant_active"));
    }

    private void insertSections(Storefront s) {
        for (var section : s.getSections()) {
            jdbc.sql("""
                            insert into merchants.storefront_sections (id, storefront_id, kind, position, enabled, settings,
                                   created_at, updated_at)
                            values (:id, :sf, :kind, :position, :enabled, cast(:settings as jsonb), :createdAt, :updatedAt)
                            """)
                    .param("id", section.getId())
                    .param("sf", s.getId())
                    .param("kind", section.getKind().code())
                    .param("position", section.getPosition())
                    .param("enabled", section.isEnabled())
                    .param("settings", json.write(section.getSettings()))
                    .param("createdAt", timestamp(section.getCreatedAt()))
                    .param("updatedAt", timestamp(section.getUpdatedAt()))
                    .update();
        }
    }

    private MapSqlParameterSource params(Storefront s) {
        return new MapSqlParameterSource()
                .addValue("id", s.getId())
                .addValue("m", s.getMerchantId())
                .addValue("slug", s.getSlug().value())
                .addValue("kind", s.getPageKind().code())
                .addValue("color", s.getBrandColor().hex())
                .addValue("logo", s.getLogoDocumentId())
                .addValue("tagline", json.i18n(s.getTagline()))
                .addValue("cta", s.getCtaLabel().code())
                .addValue("announcement", json.i18n(s.getAnnouncement()))
                .addValue("publishedAt", timestamp(s.getPublishedAt()))
                .addValue("createdAt", timestamp(s.getCreatedAt()))
                .addValue("updatedAt", timestamp(s.getUpdatedAt()));
    }

    private Storefront toDomain(ResultSet rs) throws SQLException {
        var id = rs.getString("id");
        var color = rs.getString("brand_color");
        return Storefront.builder()
                .id(id)
                .merchantId(rs.getString("merchant_id"))
                .slug(new Slug(rs.getString("slug")))
                .pageKind(CodedEnum.fromCode(PageKind.class, rs.getString("page_kind")))
                .brandColor(new BrandColor(color == null ? BrandColor.DEFAULT : color))
                .logoDocumentId(rs.getString("logo_media_id"))
                .tagline(json.fromI18n(rs.getString("tagline")))
                .ctaLabel(Objects.requireNonNullElse(
                        CodedEnums.fromCode(rs.getString("cta_label"), CtaLabel.class), CtaLabel.BOOK_VISIT))
                .announcement(json.fromI18n(rs.getString("announcement")))
                .domainClaim(ClaimColumns.read(rs))
                .publishedAt(instant(rs, "published_at"))
                .sections(sections(id))
                .createdAt(Objects.requireNonNull(instant(rs, "created_at")))
                .updatedAt(Objects.requireNonNull(instant(rs, "updated_at")))
                .build();
    }

    private List<StorefrontSection> sections(String storefrontId) {
        return new ArrayList<>(jdbc.sql("""
                        select id, kind, position, enabled, settings::text as settings, created_at, updated_at
                          from merchants.storefront_sections where storefront_id = :id order by position
                        """)
                .param("id", storefrontId)
                .query((rs, _) -> StorefrontSection.builder()
                        .id(rs.getString("id"))
                        .kind(CodedEnum.fromCode(SectionKind.class, rs.getString("kind")))
                        .position(rs.getInt("position"))
                        .enabled(rs.getBoolean("enabled"))
                        .settings(json.map(rs.getString("settings")))
                        .createdAt(Objects.requireNonNull(instant(rs, "created_at")))
                        .updatedAt(Objects.requireNonNull(instant(rs, "updated_at")))
                        .build())
                .list());
    }
}
