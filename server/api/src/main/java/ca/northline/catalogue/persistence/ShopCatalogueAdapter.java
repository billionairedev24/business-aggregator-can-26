package ca.northline.catalogue.persistence;

import ca.northline.catalogue.application.ShopCatalogue;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link ShopCatalogue}: the consumer Shop's reads (S-49). "Live" = {@code vetting = 'approved'} and
 * {@code status = 'live'} (partial indexes {@code ix_offers_live*}, V111). An offer with variants is in stock when a
 * variant is; one without, when the offer is. It goes on pooled runs unless its fulfilment options leave "pooled" out.
 */
@Repository
@RequiredArgsConstructor
class ShopCatalogueAdapter implements ShopCatalogue {

    /** Live offers of {@code :merchants} in shop categories except {@code :excluded}, optionally one category. */
    private static final String LIVE = """
            from catalogue.offers o
            join catalogue.catalog_products cp on cp.id = o.product_id
           where o.vetting = 'approved' and o.status = 'live' and o.merchant_id = any(:merchants)
             and cp.category_id like 'shop.%' and not (cp.category_id = any(:excluded))
             and (cast(:category as text) is null or cp.category_id = :category)
            """;

    /** Handling days of an in-stock pooled offer, else null. */
    static final String HANDLING = """
            case when (o.fulfilment is null or cardinality(o.fulfilment) = 0 or 'pooled' = any(o.fulfilment))
                  and (case when exists (select 1 from catalogue.variants v where v.offer_id = o.id)
                            then exists (select 1 from catalogue.variants v where v.offer_id = o.id and v.stock > 0)
                            else coalesce(case when o.listing_type = 'bundle' then catalogue.bundle_stock(o.id) else o.stock end, 0) > 0 end)
                 then case o.handling_time when 'next_day' then 1 when 'two_days' then 2 else 0 end
            end""";

    private final JdbcClient jdbc;

    @Override
    public List<Category> categories(String lang) {
        return jdbc.sql("""
                        select c.id, c.parent_id,
                               coalesce(l.name, c.name_i18n ->> :lang, c.name_i18n ->> 'en', c.id) as name
                          from catalogue.categories c
                          left join catalogue.category_labels l on l.category_id = c.id and l.lang = :lang
                         where c.root = 'shop'
                         order by c.parent_id nulls first, name
                        """)
                .param("lang", lang)
                .query((rs, _) -> new Category(rs.getString("id"), rs.getString("parent_id"), rs.getString("name")))
                .list();
    }

    @Override
    public List<ShopStats> shops(
            Collection<String> merchantIds, @Nullable String categoryId, Collection<String> excluded) {
        if (merchantIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("with live as (select o.merchant_id, o.product_id, cp.category_id, " + HANDLING
                        + " as handling " + LIVE + """
                        )
                        select merchant_id, mode() within group (order by category_id) as department_id,
                               count(distinct product_id) as products, min(handling) as handling_days
                          from live group by merchant_id
                        """)
                .param("merchants", Sql.array(merchantIds))
                .param("excluded", Sql.array(excluded))
                .param("category", categoryId)
                .query((rs, _) -> new ShopStats(
                        rs.getString("merchant_id"),
                        rs.getString("department_id"),
                        rs.getInt("products"),
                        Sql.intOrNull(rs, "handling_days")))
                .list();
    }

    @Override
    public List<ProductRow> popular(
            Collection<String> merchantIds,
            @Nullable String categoryId,
            Collection<String> excluded,
            String lang,
            int limit) {
        if (merchantIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        with live as (
                          select o.id as offer_id, o.merchant_id, o.product_id, o.sales_30d, o.created_at,
                                 coalesce((select lt.title from catalogue.listing_texts lt
                                            where lt.listing_id = o.id and lt.lang = :lang),
                                          cp.title_i18n ->> :lang, cp.title, o.title, '') as name,
                                 coalesce(cp.attributes ->> 'volume', cp.attributes ->> 'size') as unit,
                                 coalesce((select min(v.price_cents) from catalogue.variants v where v.offer_id = o.id),
                                          o.price_cents, 0) as price,
                                 coalesce(case when o.image_source = 'shared' then cp.image_set[1] end,
                                          o.own_images[1], cp.image_set[1]) as image_id,
                        """ + HANDLING + " as handling " + LIVE + """
                        ), ranked as (
                          select l.*, row_number() over (partition by product_id order by price, offer_id) as rn,
                                 count(*) over (partition by product_id) as sellers,
                                 sum(sales_30d) over (partition by product_id) as sales,
                                 max(created_at) over (partition by product_id) as newest
                            from live l)
                        select * from ranked where rn = 1
                         order by sales desc, newest desc, product_id
                         limit :limit
                        """)
                .param("merchants", Sql.array(merchantIds))
                .param("excluded", Sql.array(excluded))
                .param("category", categoryId)
                .param("lang", lang)
                .param("limit", limit)
                .query((rs, _) -> new ProductRow(
                        rs.getString("product_id"),
                        rs.getString("offer_id"),
                        rs.getString("merchant_id"),
                        rs.getString("name"),
                        rs.getString("unit"),
                        rs.getLong("price"),
                        rs.getString("image_id"),
                        Sql.intOrNull(rs, "handling"),
                        rs.getInt("sellers")))
                .list();
    }

    @Override
    public Optional<ProductRecord> product(String productId, String lang) {
        return jdbc.sql("""
                        select cp.id, coalesce(t.title, cp.title_i18n ->> :lang, cp.title, '') as name, cp.brand,
                               coalesce(t.description, cp.description) as description, cp.bullets, coalesce(cp.attributes ->> 'volume', cp.attributes ->> 'size') as unit,
                               cp.category_id, cp.image_set
                          from catalogue.catalog_products cp
                          -- S-116: a seller's own text in the page's language (the first live offer that has one)
                          left join lateral (
                            select lt.title, lt.description from catalogue.listing_texts lt
                              join catalogue.offers lo on lo.id = lt.listing_id
                             where lo.product_id = cp.id and lt.lang = :lang
                               and lo.vetting = 'approved' and lo.status = 'live'
                             order by lo.created_at, lo.id limit 1) t on true
                         where cp.id in (:id, (select o.product_id from catalogue.offers o where o.id = :id))
                           and cp.category_id like 'shop.%'
                           and exists (select 1 from catalogue.offers o where o.product_id = cp.id
                                          and o.vetting = 'approved' and o.status = 'live')
                        """)
                .param("id", productId)
                .param("lang", lang)
                .query((rs, _) -> new ProductRecord(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("brand"),
                        rs.getString("description"),
                        Sql.strings(rs, "bullets"),
                        rs.getString("unit"),
                        rs.getString("category_id"),
                        Sql.strings(rs, "image_set")))
                .optional();
    }

    @Override
    public List<OfferRow> offers(String productId, Collection<String> merchantIds) {
        if (merchantIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select o.id, o.merchant_id, o.price_cents, o.compare_at_cents, o.condition, o.low_stock_at,
                               o.returns_policy, o.variant_theme, o.image_source, o.own_images,
                               coalesce((select sum(v.stock) from catalogue.variants v where v.offer_id = o.id),
                                        case when o.listing_type = 'bundle' then catalogue.bundle_stock(o.id) else o.stock end, 0) as available,
                        """ + HANDLING + " as handling " + LIVE + " and o.product_id = :product")
                .param("merchants", Sql.array(merchantIds))
                .param("excluded", new String[0])
                .param("category", null)
                .param("product", productId)
                .query((rs, _) -> new OfferRow(
                        rs.getString("id"),
                        rs.getString("merchant_id"),
                        rs.getLong("price_cents"),
                        Sql.longOrNull(rs, "compare_at_cents"),
                        rs.getString("condition"),
                        rs.getInt("available"),
                        Sql.intOrNull(rs, "low_stock_at"),
                        rs.getString("returns_policy"),
                        Objects.requireNonNullElse(rs.getString("variant_theme"), "none"),
                        Sql.intOrNull(rs, "handling"),
                        "own".equals(rs.getString("image_source")) ? Sql.strings(rs, "own_images") : List.of()))
                .list();
    }

    @Override
    public List<VariantRow> variants(Collection<String> offerIds) {
        return jdbc.sql("""
                        select offer_id, id, coalesce(attrs ->> 'value', sku, '') as value,
                               coalesce(price_cents, 0) as price_cents, coalesce(stock, 0) as stock, image_set
                          from catalogue.variants where offer_id = any(:ids) order by offer_id, position, sku
                        """)
                .param("ids", Sql.array(offerIds))
                .query((rs, _) -> new VariantRow(
                        rs.getString("offer_id"),
                        rs.getString("id"),
                        rs.getString("value"),
                        rs.getLong("price_cents"),
                        rs.getInt("stock"),
                        Sql.strings(rs, "image_set")))
                .list();
    }

    @Override
    public List<MoreRow> moreFrom(Collection<String> merchantIds, String exceptProductId, String lang, int perShop) {
        return jdbc.sql("""
                        select merchant_id, product_id, name, price from (
                          select o.merchant_id, o.product_id,
                                 coalesce(cp.title_i18n ->> :lang, cp.title, o.title, '') as name,
                                 coalesce((select min(v.price_cents) from catalogue.variants v where v.offer_id = o.id),
                                          o.price_cents, 0) as price,
                                 row_number() over (partition by o.merchant_id
                                                    order by o.sales_30d desc, o.created_at desc, o.id) as rn
                            from catalogue.offers o join catalogue.catalog_products cp on cp.id = o.product_id
                           where o.vetting = 'approved' and o.status = 'live' and o.merchant_id = any(:merchants)
                             and o.product_id <> :except and cp.category_id like 'shop.%') ranked
                         where rn <= :per order by merchant_id, rn
                        """)
                .param("merchants", Sql.array(merchantIds))
                .param("except", exceptProductId)
                .param("lang", lang)
                .param("per", perShop)
                .query((rs, _) -> new MoreRow(
                        rs.getString("merchant_id"),
                        rs.getString("product_id"),
                        rs.getString("name"),
                        rs.getLong("price")))
                .list();
    }

    @Override
    public int productCount(Collection<String> merchantIds, String categoryId) {
        if (merchantIds.isEmpty()) {
            return 0;
        }
        var count = jdbc.sql("select count(distinct o.product_id) " + LIVE)
                .param("merchants", Sql.array(merchantIds))
                .param("excluded", new String[0])
                .param("category", categoryId)
                .query(Long.class)
                .single();
        return count == null ? 0 : count.intValue();
    }
}
