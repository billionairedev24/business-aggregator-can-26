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

    /** S-116: the merchant's own text in the page's language (catalogue.listing_texts) wins over the stored one. */
    private static final String SELECT = """
            select s.id, s.merchant_id, s.category_id,
                   coalesce(t.title, s.name_i18n ->> :lang, s.name, s.name_i18n ->> 'en', s.id) as name,
                   coalesce(t.description, s.included) as included, coalesce(s.pricing_mode, 'fixed') as pricing_mode,
                   s.price_cents, coalesce(s.duration_min, 60) as duration_min, coalesce(s.buffer_min, 0) as buffer_min,
                   coalesce(s.instant_book, false) as instant_book, s.sales_30d
              from catalogue.services s
              left join catalogue.listing_texts t on t.listing_id = s.id and t.lang = :lang
             where s.vetting = 'approved' and coalesce(s.status, 'live') = 'live'
            """;

    private static final String ORDER = " order by s.sales_30d desc, name, s.id";

    private final JdbcClient jdbc;

    @Override
    public List<Offer> inCategories(Collection<String> categoryIds, String lang) {
        if (categoryIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " and s.category_id in (:categories)" + ORDER)
                .param("categories", List.copyOf(categoryIds))
                .param("lang", lang)
                .query((rs, _) -> offer(rs))
                .list();
    }

    @Override
    public List<Offer> ofMerchant(String merchantId, String lang) {
        return jdbc.sql(SELECT + " and s.merchant_id = :merchantId" + ORDER)
                .param("merchantId", merchantId)
                .param("lang", lang)
                .query((rs, _) -> offer(rs))
                .list();
    }

    @Override
    public Optional<Offer> find(String serviceId, String lang) {
        return jdbc.sql(SELECT + " and s.id = :id")
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
