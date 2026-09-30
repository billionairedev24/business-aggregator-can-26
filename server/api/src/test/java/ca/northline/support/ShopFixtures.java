package ca.northline.support;

import ca.northline.shared.Ids;
import ca.northline.tools.CategorySeeder;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Consumer Shop fixtures (S-49…S-52): sellers in a market and their listings, straight in SQL with fresh ULIDs. Each
 * test class uses its own market (application-test.yml lists them) so the shared database's other sellers stay out.
 */
@TestComponent
@RequiredArgsConstructor
public class ShopFixtures {

    public static final String BAKERY = "shop.food-and-grocery.bakery";
    public static final String BUTCHER = "shop.food-and-grocery.butcher";
    public static final String PRODUCE = "shop.food-and-grocery.produce";
    public static final String CLOTHING = "shop.apparel.clothing";
    public static final String TOBACCO = "shop.restricted.tobacco-and-vape";

    private static volatile boolean seeded;

    private final JdbcClient jdbc;
    private final DataSource dataSource;

    /** Categories (once per JVM; the seeder is idempotent). */
    public void categories() {
        if (!seeded) {
            new CategorySeeder(dataSource).seed();
            seeded = true;
        }
    }

    /** Pauses every seller of {@code market}, so a test starts from an empty market in the shared database. */
    public void closeMarket(String market) {
        jdbc.sql("update merchants.merchants set status = 'paused' where city = ? and status = 'active'")
                .params(market)
                .update();
    }

    /** An active seller in {@code market}. */
    public String shop(String market, String name, String tier) {
        return merchant(market, name, tier, "seller", "active");
    }

    public String merchant(String market, String name, String tier, String type, String status) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into merchants.merchants (id, type, display_name, legal_name, structure, tier, status, city)
                        values (?, ?, ?, ?, 'sole', ?, ?, ?)
                        """)
                .params(id, type, name, name + " Ltd.", tier, status, market)
                .update();
        return id;
    }

    /** A listing: its own catalogue record and an approved, live offer; returns the offer. */
    public Listing listing(String merchantId, String categoryId, String title, long priceCents, int stock) {
        return listing(merchantId, categoryId, title, priceCents, stock, "approved", "live", "same_day", 0);
    }

    public Listing listing(
            String merchantId,
            String categoryId,
            String title,
            long priceCents,
            int stock,
            String vetting,
            String status,
            @Nullable String handling,
            int sales30d) {
        var productId = Ids.next();
        jdbc.sql("""
                        insert into catalogue.catalog_products
                               (id, ref, identifier_type, title, title_i18n, category_id, attributes, description, bullets, locked,
                                owner_merchant_id)
                        values (?, ?, 'none', ?, jsonb_build_object('en', ?::text), ?, '{"volume":"900 g"}', ?, '{Hand made}',
                                false, ?)
                        """)
                .params(productId, "T-" + productId, title, title, categoryId, title + " — described.", merchantId)
                .update();
        return offer(merchantId, productId, title, priceCents, stock, vetting, status, handling, sales30d);
    }

    /** Another seller's offer on an existing catalogue record. */
    public Listing offer(
            String merchantId,
            String productId,
            String title,
            long priceCents,
            int stock,
            String vetting,
            String status,
            @Nullable String handling,
            int sales30d) {
        var offerId = Ids.next();
        jdbc.sql("""
                        insert into catalogue.offers (id, product_id, merchant_id, title, sku, price_cents, stock, condition,
                               fulfilment, vetting, status, handling_time, returns_policy, sales_30d)
                        values (?, ?, ?, ?, ?, ?, ?, 'new', '{pooled,pickup}', ?, ?, ?, 'standard_14', ?)
                        """)
                .params(
                        offerId,
                        productId,
                        merchantId,
                        title,
                        "SKU-" + offerId,
                        priceCents,
                        stock,
                        vetting,
                        status,
                        handling,
                        sales30d)
                .update();
        return new Listing(productId, offerId, merchantId);
    }

    /** A variant of an offer ({@code value} like "Sliced"). */
    public String variant(String offerId, String value, long priceCents, int stock, int position) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into catalogue.variants (id, offer_id, sku, attrs, price_cents, stock, image_set, position)
                        values (?, ?, ?, jsonb_build_object('value', ?::text), ?, ?, '{}', ?)
                        """)
                .params(id, offerId, "V-" + id, value, priceCents, stock, position)
                .update();
        jdbc.sql("update catalogue.offers set variant_theme = 'size' where id = ?")
                .params(offerId)
                .update();
        return id;
    }

    public void fulfilment(String offerId, String... options) {
        jdbc.sql("update catalogue.offers set fulfilment = ? where id = ?")
                .params(options, offerId)
                .update();
    }

    public record Listing(String productId, String offerId, String merchantId) {}
}
