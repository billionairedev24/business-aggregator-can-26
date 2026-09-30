package ca.northline.catalogue.persistence;

import ca.northline.catalogue.api.ServiceOffers;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link ServiceOffers}: live, approved services only (what the vetting queue let through). */
@Repository
@RequiredArgsConstructor
class ServiceOfferQueries implements ServiceOffers {

    private static final String SELECT = """
            select id, merchant_id, category_id, coalesce(name_i18n ->> :lang, name, name_i18n ->> 'en', id) as name,
                   included, coalesce(pricing_mode, 'fixed') as pricing_mode, price_cents,
                   coalesce(duration_min, 60) as duration_min, coalesce(buffer_min, 0) as buffer_min,
                   coalesce(instant_book, false) as instant_book, sales_30d
              from catalogue.services
             where vetting = 'approved' and coalesce(status, 'live') = 'live'
            """;
    private static final String ORDER = " order by sales_30d desc, name, id";

    private final JdbcClient jdbc;

    @Override
    public List<Offer> inCategories(Collection<String> categoryIds, String lang) {
        if (categoryIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " and category_id in (:categories)" + ORDER)
                .param("categories", List.copyOf(categoryIds))
                .param("lang", lang)
                .query((rs, _) -> offer(rs))
                .list();
    }

    @Override
    public List<Offer> ofMerchant(String merchantId, String lang) {
        return jdbc.sql(SELECT + " and merchant_id = :merchantId" + ORDER)
                .param("merchantId", merchantId)
                .param("lang", lang)
                .query((rs, _) -> offer(rs))
                .list();
    }

    @Override
    public Optional<Offer> find(String serviceId, String lang) {
        return jdbc.sql(SELECT + " and id = :id")
                .param("id", serviceId)
                .param("lang", lang)
                .query((rs, _) -> offer(rs))
                .optional();
    }

    private static Offer offer(ResultSet rs) throws SQLException {
        var price = rs.getObject("price_cents", Long.class);
        return new Offer(
                rs.getString("id"),
                rs.getString("merchant_id"),
                rs.getString("category_id"),
                Objects.requireNonNull(rs.getString("name")),
                rs.getString("included"),
                rs.getString("pricing_mode"),
                price,
                rs.getInt("duration_min"),
                rs.getInt("buffer_min"),
                rs.getBoolean("instant_book"),
                rs.getInt("sales_30d"));
    }
}
