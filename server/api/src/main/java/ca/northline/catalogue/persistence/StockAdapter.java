package ca.northline.catalogue.persistence;

import ca.northline.catalogue.application.StockStore;
import ca.northline.orders.api.SellableOffers.Item;
import ca.northline.orders.api.SellableOffers.Take;
import java.util.Collection;
import java.util.Comparator;
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
                               coalesce(v.stock, case when o.listing_type = 'bundle' then catalogue.bundle_stock(o.id) else o.stock end, 0) as stock,
                               case when o.fulfilment is null or cardinality(o.fulfilment) = 0 or 'pooled' = any(o.fulfilment)
                                    then case o.handling_time when 'next_day' then 1 when 'two_days' then 2 else 0 end
                               end as handling_days,
                               coalesce(v.image_set[1], case when o.image_source = 'shared' then cp.image_set[1] end,
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
        var parts = bundleParts(take.offerId());
        if (!parts.isEmpty()) {
            return takeBundle(parts, take.qty());
        }
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
        var parts = bundleParts(take.offerId());
        if (!parts.isEmpty()) {
            parts.forEach(p -> giveBack(new Take(p.offerId(), p.variantId(), p.qty() * take.qty())));
            return;
        }
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

    /** S-65: a bundle's items ({@code qty} per bundle); empty for an ordinary offer. */
    private List<Take> bundleParts(String offerId) {
        return jdbc.sql("""
                        select bi.offer_id, bi.variant_id, bi.qty
                          from catalogue.bundle_items bi join catalogue.offers o on o.id = bi.bundle_offer_id
                         where bi.bundle_offer_id = :id and o.listing_type = 'bundle'
                         order by bi.position
                        """)
                .param("id", offerId)
                .query((rs, _) -> new Take(rs.getString("offer_id"), rs.getString("variant_id"), rs.getInt("qty")))
                .list();
    }

    /**
     * Takes every item of {@code bundles} bundles, or none: the item rows are locked first (in a stable order, so two
     * checkouts can't deadlock), and only when each has enough are they decremented.
     */
    private boolean takeBundle(List<Take> parts, int bundles) {
        var sorted = parts.stream()
                .sorted(Comparator.comparing(Take::offerId)
                        .thenComparing(t -> Objects.requireNonNullElse(t.variantId(), "")))
                .toList();
        for (var p : sorted) {
            var variant = p.variantId();
            var stock = jdbc.sql(
                            variant == null
                                    ? "select coalesce(stock, 0) from catalogue.offers where id = :id for update"
                                    : "select coalesce(stock, 0) from catalogue.variants where id = :id and offer_id = :offer"
                                            + " for update")
                    .param("id", variant == null ? p.offerId() : variant)
                    .param("offer", p.offerId())
                    .query(Integer.class)
                    .optional()
                    .orElse(0);
            if (stock < p.qty() * bundles) {
                return false;
            }
        }
        sorted.forEach(p -> take(new Take(p.offerId(), p.variantId(), p.qty() * bundles)));
        return true;
    }
}
