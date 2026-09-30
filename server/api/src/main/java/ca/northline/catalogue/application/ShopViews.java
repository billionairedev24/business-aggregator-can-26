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
     * @param packBy when the shops have it packed for the courier (S-50: "Glenmore packs at 5:45")
     */
    public record Run(
            String windowId,
            @Nullable String label,
            String day,
            Instant startsAt,
            Instant endsAt,
            Instant orderBy,
            Instant packBy,
            long feeCents,
            int households) {}

    /**
     * The product page (S-50, design 06 {@code product}): the catalogue record and every shop of the market selling it,
     * best first — in stock, then the earliest run it makes, then price.
     *
     * @param unit the record's size / volume attribute ("900 g")
     */
    public record ProductPage(
            String productId,
            String name,
            @Nullable String brand,
            @Nullable String description,
            List<String> bullets,
            @Nullable String unit,
            String departmentSlug,
            String departmentName,
            String market,
            boolean served,
            List<Offer> offers,
            @Nullable Direct direct) {

        public ProductPage {
            bullets = List.copyOf(bullets);
            offers = List.copyOf(offers);
        }
    }

    /**
     * One shop's offer.
     *
     * @param priceCents the price of the cheapest variant in stock (the offer's price without variants)
     * @param stock units available (all variants together)
     * @param lowStock at or under the shop's low-stock mark
     * @param variantTheme {@code none} | {@code size} | {@code colour} | {@code size_colour} | {@code length} | …
     * @param runs the next two pooled runs it can go on (empty when out of stock or not delivered pooled)
     * @param images approved images, main first
     * @param more other products of the same shop ("Also from Glenmore")
     */
    public record Offer(
            String offerId,
            String merchantId,
            String shopName,
            String tier,
            double rating,
            int ratingCount,
            long priceCents,
            @Nullable Long compareAtCents,
            @Nullable String condition,
            int stock,
            boolean lowStock,
            @Nullable String returnsPolicy,
            String variantTheme,
            List<Variant> variants,
            List<Run> runs,
            List<String> images,
            List<MoreItem> more) {

        public Offer {
            variants = List.copyOf(variants);
            runs = List.copyOf(runs);
            images = List.copyOf(images);
            more = List.copyOf(more);
        }
    }

    public record Variant(String variantId, String value, long priceCents, int stock) {}

    public record MoreItem(String productId, String name, long priceCents) {}

    /** The direct courier ("direct courier in 45 min", $9.99). */
    public record Direct(int etaMinutes, long feeCents) {}
}
