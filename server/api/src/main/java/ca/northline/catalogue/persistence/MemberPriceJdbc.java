package ca.northline.catalogue.persistence;

import ca.northline.catalogue.application.MemberPriceStore;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link MemberPriceStore} over {@code catalogue.offers} (V428). */
@Repository
@RequiredArgsConstructor
class MemberPriceJdbc implements MemberPriceStore {

    private final JdbcClient jdbc;

    @Override
    public Optional<Optional<Integer>> find(String merchantId, String offerId) {
        return jdbc.sql("select member_discount_pct from catalogue.offers where id = :o and merchant_id = :m")
                .param("o", offerId)
                .param("m", merchantId)
                .query((rs, _) -> Optional.ofNullable(Sql.intOrNull(rs, "member_discount_pct")))
                .optional();
    }

    @Override
    public void set(String merchantId, String offerId, @Nullable Integer pct) {
        jdbc.sql("update catalogue.offers set member_discount_pct = :pct where id = :o and merchant_id = :m")
                .param("o", offerId)
                .param("m", merchantId)
                .param("pct", pct)
                .update();
    }

    @Override
    public Map<String, Integer> live(Collection<String> offerIds) {
        var out = new HashMap<String, Integer>();
        jdbc.sql("""
                        select id, member_discount_pct from catalogue.offers
                         where id = any(:ids) and member_discount_pct is not null
                           and vetting = 'approved' and status = 'live'
                        """)
                .param("ids", offerIds.stream().distinct().limit(200).toArray(String[]::new))
                .query((rs, _) -> out.put(rs.getString("id"), rs.getInt("member_discount_pct")))
                .list();
        return Map.copyOf(out);
    }
}
