package ca.northline.catalogue.application;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Read models of the consumer Shop pages (S-49/S-50). Money in CAD cents; names in the request's language. */
public final class ShopViews {
    private ShopViews() {}

    /**
     * @param served Northline delivers goods in this market (otherwise every list is empty)
     * @param run the next pooled run customers can still order for
     */
    public record Landing(
            String market,
            boolean served,
            @Nullable Run run,
            int shopCount,
            List<DepartmentTile> departments,
            List<ShopCard> shops,
            List<ProductCard> popular) {

        public Landing {
            departments = List.copyOf(departments);
            shops = List.copyOf(shops);
            popular = List.copyOf(popular);
        }
    }

    /**
     * @param siblings the other departments of the same group that have shops in the market, this one included, in
     *     name order
     * @param onRunCount shops with something on the next run
     */
    public record Department(
            String slug,
            String name,
            String groupName,
            String market,
            boolean served,
            @Nullable Run run,
            List<DepartmentTile> siblings,
            int shopCount,
            int onRunCount,
            int productCount,
            List<ShopCard> shops,
            List<ProductCard> products) {

        public Department {
            siblings = List.copyOf(siblings);
            shops = List.copyOf(shops);
            products = List.copyOf(products);
        }
    }

    public record DepartmentTile(String slug, String name, int shops) {}

    /**
     * @param tier {@code registered} | {@code trusted} | {@code master}
     * @param run the earliest pooled run the shop has something in stock for; null when none of the upcoming ones
     */
    public record ShopCard(
            String merchantId,
            String name,
            String tier,
            String departmentSlug,
            String departmentName,
            int products,
            @Nullable Run run) {}

    /**
     * The best (cheapest) offer of a catalogue product in the market.
     *
     * @param unit the product's size / volume attribute ("900 g", "dozen"), when it has one
     * @param imageUrl an approved image, or null
     * @param sellers how many shops of the market sell it
     */
    public record ProductCard(
            String productId,
            String offerId,
            String name,
            String merchantId,
            String shopName,
            @Nullable String unit,
            long priceCents,
            @Nullable String imageUrl,
            int sellers,
            @Nullable Run run) {}

    /**
     * A pooled run as the Shop shows it.
     *
     * @param day {@code today} | {@code tomorrow} | {@code later}, in America/Edmonton
     * @param orderBy the customer's cut-off
     */
    public record Run(
            String windowId,
            @Nullable String label,
            String day,
            Instant startsAt,
            Instant endsAt,
            Instant orderBy,
            long feeCents,
            int households) {}
}
