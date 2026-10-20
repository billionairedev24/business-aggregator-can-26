package ca.northline.catalogue.persistence;

import ca.northline.orders.api.ReturnPolicies;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link ReturnPolicies} (S-151, V435): an offer's return window is its product category's own
 * ({@code catalogue.category_return_windows}), else its group's (the category's parent); offers without either are
 * left out (orders applies the platform default).
 */
@Repository
@RequiredArgsConstructor
class ReturnPoliciesJdbc implements ReturnPolicies {

    private final JdbcClient jdbc;

    @Override
    public Map<String, Integer> days(Collection<String> offerIds) {
        var out = new HashMap<String, Integer>();
        if (offerIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        select o.id, coalesce(leaf.days, grp.days) as days
                          from catalogue.offers o
                          join catalogue.catalog_products p on p.id = o.product_id
                          left join catalogue.categories c on c.id = p.category_id
                          left join catalogue.category_return_windows leaf on leaf.category_id = p.category_id
                          left join catalogue.category_return_windows grp on grp.category_id = c.parent_id
                         where o.id = any(:ids) and coalesce(leaf.days, grp.days) is not null""").param("ids", offerIds.toArray(String[]::new)).query(rs -> {
            out.put(Objects.requireNonNull(rs.getString(1)), rs.getInt(2));
        });
        return Map.copyOf(out);
    }
}
