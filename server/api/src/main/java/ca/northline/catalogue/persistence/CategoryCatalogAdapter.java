package ca.northline.catalogue.persistence;

import ca.northline.catalogue.application.CategoryCatalog;
import ca.northline.catalogue.domain.CategoryProfile;
import ca.northline.catalogue.domain.CategoryProfile.AttributeSpec;
import ca.northline.catalogue.domain.VariantTheme;
import ca.northline.shared.CodedEnum;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Categories joined with their attribute template ({@code catalogue.attribute_templates}) and the median price of
 * approved listings in the category (needs at least three to count). Banned leaves come from configuration.
 */
@Repository
@EnableConfigurationProperties(CategoryCatalogAdapter.Properties.class)
class CategoryCatalogAdapter implements CategoryCatalog {

    /**
     * {@code northline.catalogue.banned-categories}: leaves no listing may use (vetting flags them, bulk upload rejects
     * them). Default: cannabis accessories, tobacco &amp; vape.
     */
    @ConfigurationProperties("northline.catalogue")
    record Properties(
            @DefaultValue({"shop.restricted.cannabis-accessories", "shop.restricted.tobacco-and-vape"})
            List<String> bannedCategories) {}

    private static final List<VariantTheme> DEFAULT_THEMES =
            List.of(VariantTheme.SIZE, VariantTheme.COLOUR, VariantTheme.SIZE_COLOUR);

    private static final String SELECT = """
            with medians as (
              select category_id, round(percentile_cont(0.5) within group (order by price_cents))::bigint as median
                from (select cp.category_id, o.price_cents
                        from catalogue.offers o join catalogue.catalog_products cp on cp.id = o.product_id
                       where o.vetting = 'approved' and o.price_cents is not null
                      union all
                      select s.category_id, s.price_cents from catalogue.services s
                       where s.vetting = 'approved' and s.price_cents is not null) p
               group by category_id
              having count(*) >= 3)
            select c.id, c.parent_id, c.root, coalesce(c.name_i18n ->> :lang, c.name_i18n ->> 'en', c.id) as name,
                   c.regulated_registry,
                   not exists (select 1 from catalogue.categories ch where ch.parent_id = c.id) as leaf,
                   t.attributes, t.variant_themes, m.median
              from catalogue.categories c
              left join catalogue.attribute_templates t on t.category_id = c.id
              left join medians m on m.category_id = c.id
            """;

    private final JdbcClient jdbc;
    private final Set<String> banned;

    CategoryCatalogAdapter(JdbcClient jdbc, Properties properties) {
        this.jdbc = jdbc;
        this.banned = Set.copyOf(properties.bannedCategories());
    }

    @Override
    public List<CategoryProfile> all(String root, Locale locale) {
        return jdbc.sql(SELECT + " where c.root = :root order by c.parent_id is not null, name")
                .param("lang", locale.getLanguage())
                .param("root", root)
                .query((rs, _) -> map(rs))
                .list();
    }

    @Override
    public Optional<CategoryProfile> profile(String categoryId) {
        return jdbc.sql(SELECT + " where c.id = :id")
                .param("lang", "en")
                .param("id", categoryId)
                .query((rs, _) -> map(rs))
                .optional();
    }

    private CategoryProfile map(ResultSet rs) throws SQLException {
        var id = rs.getString("id");
        var themes = Sql.strings(rs, "variant_themes").stream()
                .map(code -> CodedEnum.fromCode(VariantTheme.class, code))
                .toList();
        return new CategoryProfile(
                id,
                rs.getString("parent_id"),
                Objects.requireNonNullElse(rs.getString("root"), ""),
                rs.getString("name"),
                rs.getBoolean("leaf"),
                rs.getString("regulated_registry"),
                banned.contains(id),
                id.startsWith("shop.food-and-grocery."),
                Sql.list(rs.getString("attributes"), AttributeSpec.class),
                themes.isEmpty() ? DEFAULT_THEMES : themes,
                Sql.longOrNull(rs, "median"));
    }
}
