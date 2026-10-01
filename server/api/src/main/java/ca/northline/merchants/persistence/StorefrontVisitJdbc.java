package ca.northline.merchants.persistence;

import ca.northline.merchants.api.StorefrontVisits.DayVisits;
import ca.northline.merchants.application.StorefrontVisitStore;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link StorefrontVisitStore} over {@code merchants.storefront_visits} (S-75): an upsert-increment per day. */
@Repository
@RequiredArgsConstructor
class StorefrontVisitJdbc implements StorefrontVisitStore {

    private final JdbcClient jdbc;

    @Override
    public void increment(String merchantId, LocalDate day) {
        jdbc.sql("""
                        insert into merchants.storefront_visits (merchant_id, day, visits) values (:m, :day, 1)
                        on conflict (merchant_id, day) do update set visits = merchants.storefront_visits.visits + 1
                        """).param("m", merchantId).param("day", day).update();
    }

    @Override
    public List<DayVisits> daily(String merchantId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        select day, visits from merchants.storefront_visits
                         where merchant_id = :m and day >= :from and day < :to order by day
                        """)
                .param("m", merchantId)
                .param("from", from)
                .param("to", to)
                .query((rs, _) -> new DayVisits(rs.getObject("day", LocalDate.class), rs.getInt("visits")))
                .list();
    }
}
