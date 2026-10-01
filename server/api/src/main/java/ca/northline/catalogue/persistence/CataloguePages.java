package ca.northline.catalogue.persistence;

import ca.northline.catalogue.application.CategoryCatalog;
import ca.northline.shared.JdbcTimes;
import ca.northline.shared.PublicPages;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Sitemap sections of the catalogue (S-63): {@code products} (shop products some shop sells, approved and live — what
 * {@code /products/<id>} shows), {@code departments} (shop leaf categories with such a product, {@code /shop/<slug>})
 * and {@code services} (service leaf categories with a live, approved service, {@code /services/<slug>}). Banned
 * categories are never listed. A category's key is its leaf slug, as the pages' routes use it.
 */
@Configuration(proxyBeanMethods = false)
class CataloguePages {

    private static final String LIVE_OFFER =
            "exists (select 1 from catalogue.offers o where o.product_id = p.id and o.vetting = 'approved' and o.status = 'live')";

    @Bean
    PublicPages productPages(JdbcClient jdbc, CategoryCatalog categories) {
        return new Pages(jdbc, categories, "products", """
                 from catalogue.catalog_products p
                where p.category_id like 'shop.%%' and not (p.category_id = any(:banned)) and %s
                """.formatted(LIVE_OFFER), "p.id", "p.updated_at");
    }

    @Bean
    PublicPages departmentPages(JdbcClient jdbc, CategoryCatalog categories) {
        return new Pages(
                jdbc,
                categories,
                "departments",
                """
                 from catalogue.categories c
                where c.root = 'shop' and not (c.id = any(:banned))
                  and not exists (select 1 from catalogue.categories k where k.parent_id = c.id)
                  and exists (select 1 from catalogue.catalog_products p where p.category_id = c.id and %s)
                """.formatted(LIVE_OFFER),
                "split_part(c.id, '.', -1)",
                "null::timestamptz");
    }

    @Bean
    PublicPages servicePages(JdbcClient jdbc, CategoryCatalog categories) {
        return new Pages(jdbc, categories, "services", """
                 from catalogue.categories c
                where c.root = 'service' and not (c.id = any(:banned))
                  and not exists (select 1 from catalogue.categories k where k.parent_id = c.id)
                  and exists (select 1 from catalogue.services v
                               where v.category_id = c.id and v.vetting = 'approved' and v.status = 'live')
                """, "split_part(c.id, '.', -1)", "null::timestamptz");
    }

    @RequiredArgsConstructor
    static final class Pages implements PublicPages {
        private final JdbcClient jdbc;
        private final CategoryCatalog categories;
        private final String section;
        private final String from;
        private final String key;
        private final String updated;

        @Override
        public String section() {
            return section;
        }

        private String[] banned() {
            return categories.banned().toArray(String[]::new);
        }

        @Override
        public long count() {
            return jdbc.sql("select count(*)" + from)
                    .param("banned", banned())
                    .query(Long.class)
                    .single();
        }

        @Override
        public List<Page> list(long offset, int limit) {
            return jdbc.sql("select %s as key, %s as updated_at %s order by 1 offset :offset limit :limit"
                            .formatted(key, updated, from))
                    .param("banned", banned())
                    .param("offset", offset)
                    .param("limit", limit)
                    .query((rs, _) -> new Page(rs.getString("key"), null, JdbcTimes.instant(rs, "updated_at")))
                    .list();
        }
    }
}
