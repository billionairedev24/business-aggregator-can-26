package ca.northline.catalogue.persistence;

import ca.northline.catalogue.api.CatalogueFacts;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Minimal {@link CatalogueFacts} added by the operations workstream (docs/DECISIONS.md "Operations"). */
@Repository
@RequiredArgsConstructor
class CatalogueFactsQueries implements CatalogueFacts {

    private final JdbcClient jdbc;

    @Override
    public List<LowStock> lowStock(String merchantId, String lang) {
        return jdbc.sql("""
                        select o.id, coalesce(p.title_i18n ->> :lang, p.title_i18n ->> 'en', o.sku, o.id) as name, o.stock
                          from catalogue.offers o left join catalogue.catalog_products p on p.id = o.product_id
                         where o.merchant_id = :merchantId and coalesce(o.status, 'live') = 'live'
                           and o.stock is not null and o.low_stock_at is not null and o.stock <= o.low_stock_at
                         order by o.stock, name
                        """)
                .param("merchantId", merchantId)
                .param("lang", lang)
                .query((rs, _) -> new LowStock(rs.getString("id"), rs.getString("name"), rs.getInt("stock")))
                .list();
    }

    @Override
    public List<ServiceDuration> services(String merchantId, String lang) {
        return jdbc.sql("""
                        select id, coalesce(name_i18n ->> :lang, name_i18n ->> 'en', id) as name, duration_min
                          from catalogue.services
                         where merchant_id = :merchantId and duration_min is not null and duration_min > 0
                           and coalesce(status, 'live') <> 'hidden'
                         order by name
                        """)
                .param("merchantId", merchantId)
                .param("lang", lang)
                .query((rs, _) ->
                        new ServiceDuration(rs.getString("id"), rs.getString("name"), rs.getInt("duration_min")))
                .list();
    }
}
