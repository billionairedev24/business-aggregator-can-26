package ca.northline.worker.search;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The read side the search documents are built from: read-only queries on the api's tables (the worker and the api
 * share the database, as {@code JdbcRecipients} does for notifications). Columns are read the way the owning modules
 * write them (V004/V030 merchants, V005/V050 catalogue, V006/V090/V091 food, V041 availability, V073 trust, V120
 * locations); nothing here writes. Chosen over an internal api endpoint: no service credentials between the apps, no
 * extra hop per event, and the reindex (S-71) streams whole merchants through the same queries.
 */
final class DocumentSource {

    private final JdbcClient jdbc;

    DocumentSource(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Everything the documents of one scope need, as Postgres has it now. */
    record Snapshot(
            Optional<MerchantFacts> merchant, List<ServiceRow> services, List<OfferRow> offers, List<DishRow> dishes) {}

    record MerchantFacts(
            String id,
            @Nullable String type,
            String name,
            @Nullable String status,
            @Nullable String tier,
            @Nullable Integer qualityScore,
            @Nullable String market,
            @Nullable String sameDayCutoff,
            @Nullable String slug,
            boolean pagePublished,
            @Nullable String taglineEn,
            @Nullable String taglineFr,
            @Nullable String about,
            @Nullable Double lat,
            @Nullable Double lon,
            @Nullable Double radiusKm,
            @Nullable Instant pausedUntil,
            @Nullable Integer prepMinutes,
            List<String> kitchenFulfilment,
            @Nullable Double rating,
            int reviewCount,
            Instant updatedAt,
            List<String> categoryIds,
            Map<Integer, List<String>> kitchenHours,
            Map<Integer, List<String>> serviceHours) {

        boolean active() {
            return "active".equals(status) && market != null && !market.isBlank();
        }
    }

    record ServiceRow(
            String id,
            @Nullable String categoryId,
            String nameEn,
            String nameFr,
            @Nullable String descriptionEn,
            @Nullable String descriptionFr,
            @Nullable String pricingMode,
            @Nullable Long priceCents,
            boolean instantBook,
            int sales30d,
            Instant updatedAt,
            boolean visible) {}

    record OfferRow(
            String id,
            @Nullable String categoryId,
            String nameEn,
            String nameFr,
            @Nullable String description,
            @Nullable String keywords,
            @Nullable Long priceCents,
            int stock,
            List<String> fulfilment,
            @Nullable String imageId,
            int sales30d,
            Instant updatedAt,
            boolean visible) {}

    record DishRow(
            String id,
            String nameEn,
            String nameFr,
            @Nullable String descriptionEn,
            @Nullable String descriptionFr,
            @Nullable String section,
            @Nullable Long priceCents,
            List<String> allergens,
            List<String> dietary,
            int prepAddMinutes,
            @Nullable LocalDate soldOutOn,
            @Nullable String photoKey,
            Instant updatedAt,
            boolean visible) {}

    /** The merchant and every listing and dish it has (visible or not). */
    Snapshot merchant(String merchantId) {
        return new Snapshot(
                facts(merchantId), services(merchantId, null), offers(merchantId, null), dishes(merchantId, null));
    }

    Snapshot service(String merchantId, String id) {
        return new Snapshot(facts(merchantId), services(merchantId, id), List.of(), List.of());
    }

    Snapshot offer(String merchantId, String id) {
        return new Snapshot(facts(merchantId), List.of(), offers(merchantId, id), List.of());
    }

    Snapshot dish(String merchantId, String id) {
        return new Snapshot(facts(merchantId), List.of(), List.of(), dishes(merchantId, id));
    }

    /** Merchants with anything the documents read changed since {@code since}, oldest change first. */
    List<Changed> changedSince(Instant since, int limit) {
        return jdbc.sql("""
                        select merchant_id, max(changed) as changed from (
                          select merchant_id, updated_at as changed from catalogue.services where updated_at > :since
                          union all
                          select merchant_id, updated_at from catalogue.offers where updated_at > :since
                          union all
                          select o.merchant_id, p.updated_at from catalogue.catalog_products p
                            join catalogue.offers o on o.product_id = p.id where p.updated_at > :since
                          union all
                          select merchant_id, updated_at from food.menu_items where updated_at > :since
                          union all
                          select merchant_id, updated_at from food.menus where updated_at > :since
                          union all
                          select merchant_id, updated_at from food.kitchen_settings where updated_at > :since
                          union all
                          select merchant_id, updated_at from food.opening_hours where updated_at > :since
                          union all
                          select id, updated_at from merchants.merchants where updated_at > :since
                          union all
                          select merchant_id, updated_at from merchants.storefronts where updated_at > :since
                          union all
                          select merchant_id, updated_at from merchants.locations where updated_at > :since
                          union all
                          select merchant_id, updated_at from availability.availability_rules where updated_at > :since
                          union all
                          select target_id, greatest(created_at, reply_at, reported_at) from trust.reviews
                           where target_type = 'merchant'
                             and (created_at > :since or reply_at > :since or reported_at > :since)
                        ) c
                        where merchant_id is not null
                        group by merchant_id
                        order by max(changed), merchant_id
                        limit :limit""")
                .param("since", OffsetDateTime.ofInstant(since, java.time.ZoneOffset.UTC))
                .param("limit", limit)
                .query((rs, _) -> new Changed(
                        rs.getString("merchant_id"),
                        rs.getObject("changed", OffsetDateTime.class).toInstant()))
                .list();
    }

    record Changed(String merchantId, Instant changedAt) {}

    /** Every merchant id, in id order after {@code after} (the reindex pages through them). */
    List<String> merchantIds(String after, int limit) {
        return jdbc.sql("select id from merchants.merchants where id > :after order by id limit :limit")
                .param("after", after)
                .param("limit", limit)
                .query((rs, _) -> java.util.Objects.requireNonNull(rs.getString(1)))
                .list();
    }

    private Optional<MerchantFacts> facts(String merchantId) {
        var categories = jdbc.sql("""
                        select category_id from merchants.merchant_categories
                         where merchant_id = :m and status = 'approved' order by category_id""")
                .param("m", merchantId)
                .query((rs, _) -> java.util.Objects.requireNonNull(rs.getString(1)))
                .list();
        var kitchenHours = hours("""
                select weekday, ranges::text as ranges from food.opening_hours where merchant_id = :m""", merchantId);
        // the current weekly rule of every bookable member (the latest effective one per member and weekday)
        var serviceHours = hours("""
                select weekday, ranges from (
                  select distinct on (member_user_id, weekday) weekday, ranges::text as ranges
                    from availability.availability_rules
                   where merchant_id = :m and (effective_from is null or effective_from <= current_date)
                   order by member_user_id, weekday, effective_from desc nulls last) r""", merchantId);
        return jdbc.sql("""
                        select m.id, m.type, m.display_name, m.status, m.tier, m.quality_score, m.province,
                               m.profile->>'sameDayCutoff' as same_day_cutoff, m.profile->>'description' as about,
                               m.updated_at,
                               sf.slug, sf.published_at is not null as page_published,
                               coalesce(nullif(sf.tagline_i18n->>'en', ''), sf.tagline_i18n->>'fr') as tagline_en,
                               coalesce(nullif(sf.tagline_i18n->>'fr', ''), sf.tagline_i18n->>'en') as tagline_fr,
                               st_y(l.geom::geometry) as lat, st_x(l.geom::geometry) as lon,
                               coalesce(l.service_radius_km, ks.radius_km)::float8 as radius_km,
                               ks.paused_until, ks.default_prep_min + coalesce(ks.prep_bump_min, 0) as prep_min,
                               ks.fulfilment as kitchen_fulfilment,
                               r.rating, coalesce(r.reviews, 0) as reviews
                          from merchants.merchants m
                          left join merchants.storefronts sf on sf.merchant_id = m.id
                          left join merchants.locations l on l.merchant_id = m.id
                          left join food.kitchen_settings ks on ks.merchant_id = m.id
                          left join lateral (
                            select round(avg(rating)::numeric, 2)::float8 as rating, count(*) as reviews
                              from trust.reviews where target_type = 'merchant' and target_id = m.id) r on true
                         where m.id = :m""")
                .param("m", merchantId)
                .query((rs, _) -> new MerchantFacts(
                        rs.getString("id"),
                        rs.getString("type"),
                        rs.getString("display_name"),
                        rs.getString("status"),
                        rs.getString("tier"),
                        (Integer) rs.getObject("quality_score"),
                        rs.getString("province"),
                        rs.getString("same_day_cutoff"),
                        rs.getString("slug"),
                        rs.getBoolean("page_published"),
                        rs.getString("tagline_en"),
                        rs.getString("tagline_fr"),
                        rs.getString("about"),
                        (Double) rs.getObject("lat"),
                        (Double) rs.getObject("lon"),
                        (Double) rs.getObject("radius_km"),
                        instant(rs, "paused_until"),
                        (Integer) rs.getObject("prep_min"),
                        strings(rs.getArray("kitchen_fulfilment")),
                        (Double) rs.getObject("rating"),
                        rs.getInt("reviews"),
                        java.util.Objects.requireNonNull(instant(rs, "updated_at")),
                        categories,
                        kitchenHours,
                        serviceHours))
                .optional();
    }

    private Map<Integer, List<String>> hours(String sql, String merchantId) {
        var byDay = new HashMap<Integer, List<String>>();
        jdbc.sql(sql)
                .param("m", merchantId)
                .query((rs, _) -> {
                    byDay.computeIfAbsent(rs.getInt("weekday"), _ -> new ArrayList<>())
                            .add(rs.getString("ranges"));
                    return null;
                })
                .list();
        return byDay;
    }

    private List<ServiceRow> services(String merchantId, @Nullable String id) {
        return jdbc.sql("""
                        select s.id, s.category_id, s.pricing_mode, s.price_cents, coalesce(s.instant_book, false) as instant,
                               s.sales_30d, s.updated_at,
                               coalesce(s.vetting = 'approved' and s.status = 'live', false) as visible,
                               coalesce(nullif(s.name_i18n->>'en', ''), s.name, s.name_i18n->>'fr', '') as name_en,
                               coalesce(nullif(s.name_i18n->>'fr', ''), s.name, s.name_i18n->>'en', '') as name_fr,
                               coalesce(nullif(s.desc_i18n->>'en', ''), s.included) as desc_en,
                               coalesce(nullif(s.desc_i18n->>'fr', ''), s.included, s.desc_i18n->>'en') as desc_fr
                          from catalogue.services s
                         where s.merchant_id = :m and (cast(:id as text) is null or s.id = :id)
                         order by s.id""")
                .param("m", merchantId)
                .param("id", id)
                .query((rs, _) -> new ServiceRow(
                        rs.getString("id"),
                        rs.getString("category_id"),
                        rs.getString("name_en"),
                        rs.getString("name_fr"),
                        rs.getString("desc_en"),
                        rs.getString("desc_fr"),
                        rs.getString("pricing_mode"),
                        (Long) rs.getObject("price_cents"),
                        rs.getBoolean("instant"),
                        rs.getInt("sales_30d"),
                        java.util.Objects.requireNonNull(instant(rs, "updated_at")),
                        rs.getBoolean("visible")))
                .list();
    }

    private List<OfferRow> offers(String merchantId, @Nullable String id) {
        return jdbc.sql("""
                        select o.id, p.category_id, o.price_cents, o.fulfilment, o.sales_30d,
                               greatest(o.updated_at, p.updated_at) as updated_at,
                               coalesce(o.vetting = 'approved' and o.status = 'live', false) as visible,
                               coalesce(case when o.listing_type = 'bundle' then catalogue.bundle_stock(o.id)
                                             else o.stock end, 0)
                                 + coalesce((select sum(v.stock) from catalogue.variants v where v.offer_id = o.id), 0)
                                 as stock,
                               coalesce(nullif(o.title, ''), nullif(p.title_i18n->>'en', ''), p.title, '') as name_en,
                               coalesce(nullif(p.title_i18n->>'fr', ''), nullif(o.title, ''), p.title, '') as name_fr,
                               nullif(concat_ws(' ', p.description, array_to_string(p.bullets, ' ')), '') as description,
                               nullif(concat_ws(' ', o.search_keywords, p.brand), '') as keywords,
                               case when o.image_source = 'shared' then p.image_set[1]
                                    else coalesce(o.own_images[1], p.image_set[1]) end as image
                          from catalogue.offers o
                          join catalogue.catalog_products p on p.id = o.product_id
                         where o.merchant_id = :m and (cast(:id as text) is null or o.id = :id)
                         order by o.id""")
                .param("m", merchantId)
                .param("id", id)
                .query((rs, _) -> new OfferRow(
                        rs.getString("id"),
                        rs.getString("category_id"),
                        rs.getString("name_en"),
                        rs.getString("name_fr"),
                        rs.getString("description"),
                        rs.getString("keywords"),
                        (Long) rs.getObject("price_cents"),
                        rs.getInt("stock"),
                        strings(rs.getArray("fulfilment")),
                        rs.getString("image"),
                        rs.getInt("sales_30d"),
                        java.util.Objects.requireNonNull(instant(rs, "updated_at")),
                        rs.getBoolean("visible")))
                .list();
    }

    private List<DishRow> dishes(String merchantId, @Nullable String id) {
        return jdbc.sql("""
                        select i.id, i.price_cents, i.allergens, i.dietary, coalesce(i.prep_add_min, 0) as prep_add,
                               i.sold_out_on, i.photo_key, i.updated_at,
                               (i.status = 'published' and i.vetting = 'approved' and mn.status = 'live') as visible,
                               coalesce(nullif(i.name_i18n->>'en', ''), i.name, i.name_i18n->>'fr', '') as name_en,
                               coalesce(nullif(i.name_i18n->>'fr', ''), i.name, i.name_i18n->>'en', '') as name_fr,
                               coalesce(nullif(i.desc_i18n->>'en', ''), i.description) as desc_en,
                               coalesce(nullif(i.desc_i18n->>'fr', ''), i.description, i.desc_i18n->>'en') as desc_fr,
                               coalesce(sec.name, sec.name_i18n->>'en') as section
                          from food.menu_items i
                          join food.menu_sections sec on sec.id = i.section_id
                          join food.menus mn on mn.id = sec.menu_id
                         where i.merchant_id = :m and (cast(:id as text) is null or i.id = :id)
                         order by i.id""")
                .param("m", merchantId)
                .param("id", id)
                .query((rs, _) -> new DishRow(
                        rs.getString("id"),
                        rs.getString("name_en"),
                        rs.getString("name_fr"),
                        rs.getString("desc_en"),
                        rs.getString("desc_fr"),
                        rs.getString("section"),
                        (Long) rs.getObject("price_cents"),
                        strings(rs.getArray("allergens")),
                        strings(rs.getArray("dietary")),
                        rs.getInt("prep_add"),
                        rs.getObject("sold_out_on", LocalDate.class),
                        rs.getString("photo_key"),
                        java.util.Objects.requireNonNull(instant(rs, "updated_at")),
                        rs.getBoolean("visible")))
                .list();
    }

    private static List<String> strings(@Nullable Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return Arrays.stream((Object[]) array.getArray())
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .toList();
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
