package ca.northline.catalogue.application;

import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The consumer Shop (S-49, design 06 {@code shop} and {@code category}): only approved, live offers of active sellers,
 * in shop categories that aren't banned. A market is a city Northline delivers in (a live market of the region model).
 *
 * <p>S-190 (a location is never required): with no {@link Place#market()} the pages show every market Northline
 * delivers in together, ranked by popularity (nearest market first when the visitor's point is known), and nothing
 * claims a delivery run. With a market the pages keep to it, and what the nearest other markets sell is listed apart
 * ({@code nearby}, {@code elsewhere}), labelled "not available for delivery to you" instead of being hidden.
 */
public interface BrowseShop {

    /**
     * Where the visitor is, as far as the page knows.
     *
     * @param market a city, or null when it isn't known
     * @param lat the visitor's point (both or neither), used to order markets by distance
     */
    record Place(
            @Nullable String market,
            @Nullable Double lat,
            @Nullable Double lng) {

        public static Place market(@Nullable String market) {
            return new Place(market, null, null);
        }

        public static Place anywhere() {
            return new Place(null, null, null);
        }
    }

    /** The landing page: departments, the shops on the next pooled run, popular products. */
    ShopViews.Landing landing(Place place, Locale locale);

    /** A department (a leaf of the shop taxonomy, by its slug: {@code bakery}); empty for an unknown slug. */
    Optional<ShopViews.Department> department(String slug, Place place, Locale locale);

    /**
     * A catalogue product with the market's offers (S-50). Empty when the product isn't a shop product or no shop
     * anywhere sells it (an unvetted record is never public); a product sold only elsewhere has no offers, and its
     * offers in other markets are listed in {@code elsewhere}. An offer id (a search result, S-48) names its product;
     * the page carries the product's own id.
     */
    Optional<ShopViews.ProductPage> product(String productId, Place place, Locale locale);
}
