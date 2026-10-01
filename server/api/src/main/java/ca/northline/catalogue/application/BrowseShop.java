package ca.northline.catalogue.application;

import java.util.Locale;
import java.util.Optional;

/**
 * The consumer Shop (S-49, design 06 {@code shop} and {@code category}): only approved, live offers of active sellers
 * of the market, in shop categories that aren't banned. A market is a city Northline delivers in (a live market of
 * the region model); anywhere else the pages are empty.
 */
public interface BrowseShop {

    /** The landing page: departments, the shops on the next pooled run, popular products. */
    ShopViews.Landing landing(String market, Locale locale);

    /** A department (a leaf of the shop taxonomy, by its slug: {@code bakery}); empty for an unknown slug. */
    Optional<ShopViews.Department> department(String slug, String market, Locale locale);

    /**
     * A catalogue product with the market's offers (S-50). Empty when the product isn't a shop product or no shop
     * anywhere sells it (an unvetted record is never public); a product sold only elsewhere has no offers. An offer id
     * (a search result, S-48) names its product; the page carries the product's own id.
     */
    Optional<ShopViews.ProductPage> product(String productId, String market, Locale locale);
}
