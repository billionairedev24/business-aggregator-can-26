package ca.northline.catalogue.persistence;

import ca.northline.catalogue.api.ListingCategories;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link ListingCategories} over {@code catalogue.offers} → {@code catalog_products} and {@code catalogue.services}. */
@Repository
@RequiredArgsConstructor
class ListingCategoriesJdbc implements ListingCategories {

    private final JdbcClient jdbc;

    @Override
    public Map<String, String> categories(Collection<String> listingIds) {
        var out = new HashMap<String, String>();
        if (listingIds.isEmpty()) {
            return out;
        }
        var ids = listingIds.toArray(String[]::new);
        jdbc.sql("""
                        select o.id, p.category_id from catalogue.offers o
                          join catalogue.catalog_products p on p.id = o.product_id
                         where o.id = any(:ids) and p.category_id is not null
                        union all
                        select s.id, s.category_id from catalogue.services s
                         where s.id = any(:ids) and s.category_id is not null""").param("ids", ids).query(rs -> {
            out.put(Objects.requireNonNull(rs.getString(1)), Objects.requireNonNull(rs.getString(2)));
        });
        return out;
    }
}
