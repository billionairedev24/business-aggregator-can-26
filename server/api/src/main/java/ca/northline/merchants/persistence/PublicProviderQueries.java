package ca.northline.merchants.persistence;

import ca.northline.merchants.api.PublicProviders;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link PublicProviders}: the business, its published page and its verified checks, in one query. */
@Repository
@RequiredArgsConstructor
class PublicProviderQueries implements PublicProviders {

    private static final String SELECT = """
            select m.id, s.slug, m.display_name, m.type, coalesce(m.tier, 'registered') as tier, m.city,
                   s.brand_color, coalesce(s.tagline_i18n ->> :lang, s.tagline_i18n ->> 'en') as tagline,
                   m.profile ->> 'description' as about, s.logo_media_id,
                   coalesce(m.approved_at, m.created_at) as since,
                   array(select v.check_key from merchants.verifications v
                          where v.merchant_id = m.id and v.status = 'verified'
                            and (v.check_key in ('kyc', 'insurance', 'site_visit') or v.check_key like 'licence:%')
                          order by v.position, v.check_key) as facts
              from merchants.merchants m
              join merchants.storefronts s on s.merchant_id = m.id
             where m.status = 'active' and m.type in ('provider', 'both') and s.published_at is not null
            """;

    private final JdbcClient jdbc;

    @Override
    public List<Provider> published(Collection<String> merchantIds, String lang) {
        if (merchantIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " and m.id in (:ids) order by m.display_name, m.id")
                .param("ids", List.copyOf(merchantIds))
                .param("lang", lang)
                .query((rs, _) -> provider(rs))
                .list();
    }

    @Override
    public Optional<Provider> bySlug(String slug, String lang) {
        return jdbc.sql(SELECT + " and s.slug = :slug")
                .param("slug", slug)
                .param("lang", lang)
                .query((rs, _) -> provider(rs))
                .optional();
    }

    private static Provider provider(ResultSet rs) throws SQLException {
        var slug = rs.getString("slug");
        return new Provider(
                rs.getString("id"),
                slug,
                rs.getString("display_name"),
                rs.getString("type"),
                rs.getString("tier"),
                rs.getString("city"),
                Objects.requireNonNullElse(rs.getString("brand_color"), "#1e4d36"),
                rs.getString("tagline"),
                rs.getString("about"),
                rs.getString("logo_media_id") == null ? null : "/api/v1/storefronts/%s/logo".formatted(slug),
                strings(rs.getArray("facts")),
                rs.getObject("since", OffsetDateTime.class).toInstant());
    }

    private static List<String> strings(@Nullable Array array) throws SQLException {
        return array == null
                ? List.of()
                : Arrays.stream((Object[]) array.getArray())
                        .map(String::valueOf)
                        .toList();
    }
}
