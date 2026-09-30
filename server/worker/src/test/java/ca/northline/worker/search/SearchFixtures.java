package ca.northline.worker.search;

import ca.northline.worker.support.Events;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Rows as the api writes them, for the search tests: fresh ids every time (the database is shared, never wiped). */
final class SearchFixtures {

    private static final String GROUP = "test.search.automotive";
    private static final String LEAF = "test.search.automotive.mobile-mechanic";
    private static final String SHOP = "test.search.auto-parts";
    private static final String FOOD = "test.search.vietnamese";

    private final JdbcClient jdbc;

    SearchFixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
        jdbc.sql("""
                        insert into catalogue.categories (id, parent_id, root, name_i18n) values
                          (:g, null, 'service', '{"en":"Test automotive","fr":"Automobile (test)"}'),
                          (:l, :g, 'service', '{"en":"Test mobile mechanic","fr":"Mécanicien mobile (test)"}'),
                          (:s, null, 'shop', '{"en":"Test auto parts"}'),
                          (:f, null, 'food', '{"en":"Test Vietnamese","fr":"Vietnamien (test)"}')
                        on conflict (id) do nothing""")
                .param("g", GROUP)
                .param("l", LEAF)
                .param("s", SHOP)
                .param("f", FOOD)
                .update();
    }

    record Merchant(String id, String slug) {}

    record Menu(String menuId, String sectionId) {}

    String group() {
        return GROUP;
    }

    String leaf() {
        return LEAF;
    }

    /** An active AB merchant with a published page, a location near downtown Calgary and one approved category. */
    Merchant merchant(String type, String tier, String name) {
        var id = Events.id();
        var slug = "t-" + id.toLowerCase(Locale.ROOT);
        jdbc.sql("""
                        insert into merchants.merchants (id, type, display_name, legal_name, status, tier, province,
                                                          quality_score)
                        values (:id, :type, :name, :name, 'active', :tier, 'AB', 80)""")
                .param("id", id)
                .param("type", type)
                .param("name", name)
                .param("tier", tier)
                .update();
        jdbc.sql("""
                        insert into merchants.storefronts (id, merchant_id, slug, page_kind, tagline_i18n, published_at)
                        values (:sf, :id, :slug, :kind, '{"en":"Trusted locals","fr":"Des gens d''ici"}', now())""")
                .param("sf", Events.id())
                .param("id", id)
                .param("slug", slug)
                .param(
                        "kind",
                        switch (type) {
                            case "seller" -> "store";
                            case "kitchen" -> "menu_page";
                            default -> "business_page";
                        })
                .update();
        jdbc.sql("""
                        insert into merchants.locations (merchant_id, geom, service_radius_km, source)
                        values (:id, ST_GeogFromText('POINT(-114.088 51.0379)'), :radius, 'seed')""")
                .param("id", id)
                .param("radius", type.equals("kitchen") ? null : 40)
                .update();
        jdbc.sql("""
                        insert into merchants.merchant_categories (merchant_id, category_id, status)
                        values (:id, :c, 'approved')""")
                .param("id", id)
                .param(
                        "c",
                        switch (type) {
                            case "seller" -> SHOP;
                            case "kitchen" -> FOOD;
                            default -> LEAF;
                        })
                .update();
        return new Merchant(id, slug);
    }

    void review(Merchant m, int rating) {
        jdbc.sql("""
                        insert into trust.reviews (id, ref_type, ref_id, author_id, target_type, target_id, rating)
                        values (:id, 'booking', :ref, :author, 'merchant', :m, :rating)""")
                .param("id", Events.id())
                .param("ref", Events.id())
                .param("author", Events.id())
                .param("m", m.id())
                .param("rating", rating)
                .update();
    }

    /** One member's weekly hours on an ISO weekday. */
    void weeklyHours(Merchant m, int weekday, String ranges) {
        jdbc.sql("""
                        insert into availability.availability_rules (id, merchant_id, member_user_id, weekday, ranges)
                        values (:id, :m, :member, :d, cast(:r as jsonb))""")
                .param("id", Events.id())
                .param("m", m.id())
                .param("member", Events.id())
                .param("d", weekday)
                .param("r", ranges)
                .update();
    }

    String service(Merchant m, String name, @Nullable String nameFr, long priceCents, String status) {
        var id = Events.id();
        jdbc.sql("""
                        insert into catalogue.services (id, merchant_id, category_id, name, name_i18n, pricing_mode,
                                                        price_cents, duration_min, instant_book, vetting, status)
                        values (:id, :m, :c, :name, cast(:i18n as jsonb), 'fixed', :price, 60, true, 'approved', :status)""")
                .param("id", id)
                .param("m", m.id())
                .param("c", LEAF)
                .param("name", name)
                .param("i18n", nameFr == null ? "{}" : "{\"fr\":\"" + nameFr + "\"}")
                .param("price", priceCents)
                .param("status", status)
                .update();
        return id;
    }

    String offer(Merchant m, String title, long priceCents, int stock, String fulfilment) {
        var product = Events.id();
        var id = Events.id();
        jdbc.sql("insert into catalogue.catalog_products (id, title, category_id) values (:id, :title, :c)")
                .param("id", product)
                .param("title", title)
                .param("c", SHOP)
                .update();
        jdbc.sql("""
                        insert into catalogue.offers (id, product_id, merchant_id, title, sku, price_cents, stock, condition,
                                                      fulfilment, vetting, status)
                        values (:id, :p, :m, :title, :sku, :price, :stock, 'new', cast(:f as text[]), 'approved', 'live')""")
                .param("id", id)
                .param("p", product)
                .param("m", m.id())
                .param("title", title)
                .param("sku", "SKU-" + id)
                .param("price", priceCents)
                .param("stock", stock)
                .param("f", fulfilment)
                .update();
        return id;
    }

    /** Kitchen settings (25 min prep, courier + pickup, 6 km) and Friday hours 11:00–21:00. */
    void kitchen(Merchant m) {
        jdbc.sql("""
                        insert into food.kitchen_settings (merchant_id, default_prep_min, max_orders_per_15, fulfilment,
                                                           radius_km)
                        values (:m, 25, 6, '{courier,pickup}', 6)""").param("m", m.id()).update();
        jdbc.sql("""
                        insert into food.opening_hours (merchant_id, weekday, ranges)
                        values (:m, 5, '[["11:00","21:00"]]')""").param("m", m.id()).update();
    }

    Menu menu(Merchant m, String status) {
        var menu = Events.id();
        var section = Events.id();
        jdbc.sql("insert into food.menus (id, merchant_id, name, status) values (:id, :m, 'Dinner', :s)")
                .param("id", menu)
                .param("m", m.id())
                .param("s", status)
                .update();
        jdbc.sql("insert into food.menu_sections (id, menu_id, name, sort) values (:id, :menu, 'Soups', 0)")
                .param("id", section)
                .param("menu", menu)
                .update();
        return new Menu(menu, section);
    }

    String dish(Merchant m, Menu menu, String name, String nameFr, long priceCents, String status, String vetting) {
        var id = Events.id();
        jdbc.sql("""
                        insert into food.menu_items (id, section_id, merchant_id, name, name_i18n, price_cents, allergens,
                                                     dietary, prep_add_min, status, vetting, photo_key)
                        values (:id, :section, :m, :name, cast(:i18n as jsonb), :price, '{peanuts}', '{halal}', 5,
                                :status, :vetting, 'food/photo.jpg')""")
                .param("id", id)
                .param("section", menu.sectionId())
                .param("m", m.id())
                .param("name", name)
                .param("i18n", "{\"fr\":\"" + nameFr + "\"}")
                .param("price", priceCents)
                .param("status", status)
                .param("vetting", vetting)
                .update();
        return id;
    }
}
