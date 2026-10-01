package ca.northline.merchants.persistence;

import ca.northline.shared.JdbcTimes;
import ca.northline.shared.PublicPages;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Sitemap sections of published business pages (S-63): {@code providers} ({@code /providers/<slug>}, with the live
 * custom domain that serves the page instead) and {@code kitchens} ({@code /food/<slug>}) — active businesses with a
 * published page, as {@code PublicProviders} and {@code PublicDirectory} show them.
 */
@Configuration(proxyBeanMethods = false)
class BusinessPages {

    @Bean
    PublicPages providerPages(JdbcClient jdbc) {
        return new Pages(jdbc, "providers", "m.type in ('provider', 'both')");
    }

    @Bean
    PublicPages kitchenPages(JdbcClient jdbc) {
        return new Pages(jdbc, "kitchens", "m.type = 'kitchen'");
    }

    @RequiredArgsConstructor
    static final class Pages implements PublicPages {
        private final JdbcClient jdbc;
        private final String section;
        private final String types;

        private String from() {
            return """
                     from merchants.storefronts s
                     join merchants.merchants m on m.id = s.merchant_id
                    where m.status = 'active' and s.published_at is not null and s.slug is not null and %s
                    """.formatted(types);
        }

        @Override
        public String section() {
            return section;
        }

        @Override
        public long count() {
            return jdbc.sql("select count(*)" + from()).query(Long.class).single();
        }

        @Override
        public List<Page> list(long offset, int limit) {
            return jdbc.sql("""
                            select s.slug, s.updated_at,
                                   case when s.custom_domain_status = 'live' then s.custom_domain end as domain
                            """ + from() + " order by s.slug offset :offset limit :limit")
                    .param("offset", offset)
                    .param("limit", limit)
                    .query((rs, _) ->
                            new Page(rs.getString("slug"), rs.getString("domain"), JdbcTimes.instant(rs, "updated_at")))
                    .list();
        }
    }
}
