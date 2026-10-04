package ca.northline.catalogue.persistence;

import ca.northline.catalogue.application.LicenceHolds;
import ca.northline.region.api.AgeClass;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link LicenceHolds} over {@code catalogue.offers.licence_hold} and {@code catalogue.age_class_of} (V341). */
@Repository
@RequiredArgsConstructor
class LicenceHoldsJdbc implements LicenceHolds {

    private final JdbcClient jdbc;

    @Override
    public void hold(String offerId, boolean held) {
        jdbc.sql("update catalogue.offers set licence_hold = :h where id = :id")
                .param("h", held)
                .param("id", offerId)
                .update();
    }

    @Override
    public List<String> liveOffers(String merchantId, AgeClass ageClass) {
        return offers(merchantId, ageClass, "o.vetting = 'approved' and o.status = 'live'");
    }

    @Override
    public List<String> heldOffers(String merchantId, AgeClass ageClass) {
        return offers(merchantId, ageClass, "o.licence_hold");
    }

    private List<String> offers(String merchantId, AgeClass ageClass, String where) {
        return jdbc.sql("""
                        select o.id from catalogue.offers o join catalogue.catalog_products cp on cp.id = o.product_id
                         where o.merchant_id = :m and catalogue.age_class_of(cp.category_id) = :c and\s""" + where)
                .param("m", merchantId)
                .param("c", ageClass.code())
                .query((rs, _) -> rs.getString(1))
                .list();
    }
}
