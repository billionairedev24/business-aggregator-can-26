package ca.northline.catalogue.persistence;

import ca.northline.catalogue.application.StockStore;
import ca.northline.orders.api.SellableOffers.Item;
import ca.northline.orders.api.SellableOffers.Take;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link StockStore}: live offers (and their variants) for the cart, and stock taken with a conditional decrement
 * ({@code stock = stock - :qty where stock >= :qty}), which two concurrent checkouts can't both pass for the last unit.
 */
@Repository
@RequiredArgsConstructor
class StockAdapter implements StockStore {

    private final JdbcClient jdbc;

    @Override
    public List<Row> rows(Collection<Item> items, String lang) {
        var offers = items.stream().map(Item::offerId).distinct().toArray(String[]::new);
        var rows = jdbc.sql("""
                        select o.id as offer_id, v.id as variant_id, o.product_id, o.merchant_id,
                               coalesce(cp.title_i18n ->> :lang, cp.title, o.title, '') as name,
                               v.attrs ->> 'value' as option,
                               coalesce(cp.attributes ->> 'volume', cp.attributes ->> 'size') as unit,
                               exists (select 1 from catalogue.variants x where x.offer_id = o.id) as has_variants,
                               coalesce(v.price_cents, o.price_cents, 0) as unit_cents,
                               coalesce(v.stock, o.stock, 0) as stock,
                               case when o.fulfilment is null or cardinality(o.fulfilment) = 0 or 'pooled' = any(o.fulfilment)
                                    then case o.handling_time when 'next_day' then 1 when 'two_days' then 2 else 0 end
                               end as handling_days,
                               coalesce(case when o.image_source = 'shared' then cp.image_set[1] end,
                                        o.own_images[1], cp.image_set[1]) as image_id
                          from catalogue.offers o
                          join catalogue.catalog_products cp on cp.id = o.product_id
                          left join catalogue.variants v on v.offer_id = o.id
                         where o.id = any(:offers) and o.vetting = 'approved' and o.status = 'live'
                           and cp.category_id like 'shop.%'
                        """)
                .param("offers", offers)
                .param("lang", lang)
                .query((rs, _) -> new Row(
                        rs.getString("offer_id"),
                        rs.getString("variant_id"),
                        rs.getString("product_id"),
                        rs.getString("merchant_id"),
                        rs.getString("name"),
                        rs.getString("option"),
                        rs.getString("unit"),
                        rs.getBoolean("has_variants"),
                        rs.getLong("unit_cents"),
                        rs.getInt("stock"),
                        Sql.intOrNull(rs, "handling_days"),
                        rs.getString("image_id")))
                .list();
        // an offer without variants appears once with variant_id null; with variants, once per variant — all of them
        // when the item names none (the caller then asks to choose one)
        return rows.stream()
                .filter(r -> items.stream()
                        .anyMatch(i -> i.offerId().equals(r.offerId())
                                && (Objects.equals(i.variantId(), r.variantId())
                                        || (i.variantId() == null && r.hasVariants()))))
                .toList();
    }

    @Override
    public boolean take(Take take) {
        var variant = take.variantId();
        var sql = variant == null
                ? "update catalogue.offers set stock = stock - :qty, updated_at = now() where id = :id and stock >= :qty"
                : "update catalogue.variants set stock = stock - :qty where id = :id and offer_id = :offer and stock >= :qty";
        return jdbc.sql(sql)
                        .param("qty", take.qty())
                        .param("id", variant == null ? take.offerId() : variant)
                        .param("offer", take.offerId())
                        .update()
                == 1;
    }

    @Override
    public void giveBack(Take take) {
        var variant = take.variantId();
        var sql = variant == null
                ? "update catalogue.offers set stock = coalesce(stock, 0) + :qty, updated_at = now() where id = :id"
                : "update catalogue.variants set stock = coalesce(stock, 0) + :qty where id = :id and offer_id = :offer";
        jdbc.sql(sql)
                .param("qty", take.qty())
                .param("id", variant == null ? take.offerId() : variant)
                .param("offer", take.offerId())
                .update();
    }
}
