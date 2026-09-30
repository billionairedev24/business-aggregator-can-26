package ca.northline.catalogue.persistence;

import ca.northline.catalogue.application.SalesFigures;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code sales_30d} on offers and services, rewritten per merchant; rows that don't change aren't touched. */
@Repository
@RequiredArgsConstructor
class SalesFiguresAdapter implements SalesFigures {

    private final JdbcClient jdbc;

    @Override
    public void replace(String merchantId, Map<String, Long> offers, Map<String, Long> services) {
        write("catalogue.offers", merchantId, offers);
        write("catalogue.services", merchantId, services);
    }

    private void write(String table, String merchantId, Map<String, Long> counts) {
        var entries = List.copyOf(counts.entrySet());
        var ids = entries.stream().map(Map.Entry::getKey).toArray(String[]::new);
        var values = entries.stream()
                .map(e -> (int) Math.min(Integer.MAX_VALUE, e.getValue()))
                .toArray(Integer[]::new);
        jdbc.sql("""
                        update %s t set sales_30d = coalesce(c.n, 0)
                          from %s t2 left join unnest(cast(:ids as text[]), cast(:counts as integer[])) as c(id, n)
                               on c.id = t2.id
                         where t2.merchant_id = :m and t.id = t2.id and t.sales_30d is distinct from coalesce(c.n, 0)
                        """.formatted(table, table))
                .param("m", merchantId)
                .param("ids", ids)
                .param("counts", values)
                .update();
    }

    @Override
    public List<String> merchantsWithSales() {
        return jdbc.sql("""
                        select merchant_id from catalogue.offers where sales_30d > 0 and merchant_id is not null
                        union
                        select merchant_id from catalogue.services where sales_30d > 0 and merchant_id is not null
                        """)
                .query((rs, _) -> java.util.Objects.requireNonNull(rs.getString("merchant_id")))
                .list();
    }
}
