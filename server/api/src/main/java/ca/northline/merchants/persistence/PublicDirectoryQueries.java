package ca.northline.merchants.persistence;

import ca.northline.merchants.api.PublicDirectory;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link PublicDirectory}: active businesses with their storefront slug, approved categories and public profile. */
@Repository
@RequiredArgsConstructor
class PublicDirectoryQueries implements PublicDirectory {

    private static final String SELECT = """
            select m.id, m.display_name, m.type, m.tier, m.city, m.province,
                   s.slug, s.brand_color,
                   coalesce(array(select c.category_id from merchants.merchant_categories c
                                   where c.merchant_id = m.id and c.status = 'approved'
                                   order by c.category_id), '{}') as category_ids,
                   coalesce(array(select jsonb_array_elements_text(coalesce(m.profile -> 'cuisines', '[]'))), '{}') as cuisines,
                   coalesce(array(select jsonb_array_elements_text(coalesce(m.profile -> 'dietary', '[]'))), '{}') as dietary,
                   coalesce(m.profile ->> 'kitchenAddress', m.profile ->> 'pickupAddress') as address
              from merchants.merchants m
              left join lateral (select slug, brand_color from merchants.storefronts
                                  where merchant_id = m.id and published_at is not null
                                  order by published_at desc limit 1) s on true
             where m.status = 'active'
            """;

    private final JdbcClient jdbc;

    @Override
    public List<PublicBusiness> active(Collection<String> types, String city) {
        if (types.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " and m.type in (:types) and lower(m.city) = lower(:city) order by m.display_name")
                .param("types", types)
                .param("city", city.strip())
                .query((rs, _) -> row(rs))
                .list();
    }

    @Override
    public Optional<PublicBusiness> bySlug(String slug) {
        return jdbc.sql(SELECT + " and s.slug = :slug")
                .param("slug", slug)
                .query((rs, _) -> row(rs))
                .optional();
    }

    private static PublicBusiness row(ResultSet rs) throws SQLException {
        return new PublicBusiness(
                rs.getString("id"),
                rs.getString("display_name"),
                rs.getString("type"),
                rs.getString("tier"),
                rs.getString("city"),
                rs.getString("province"),
                rs.getString("slug"),
                rs.getString("brand_color"),
                strings(rs.getArray("category_ids")),
                strings(rs.getArray("cuisines")),
                strings(rs.getArray("dietary")),
                rs.getString("address"));
    }

    private static List<String> strings(@Nullable Array array) throws SQLException {
        return array == null
                ? List.of()
                : Arrays.stream((Object[]) array.getArray())
                        .map(String::valueOf)
                        .toList();
    }
}
