package ca.northline.searchindex;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * One document of {@code listings_en} / {@code listings_fr} — the shape of {@code deploy/search/listings.json},
 * written by the worker (S-43) and read back by the search API (S-44). Text fields are in the index's language (French
 * where a translation exists, the listing's own text otherwise). Only customer-visible things are ever indexed:
 * approved and live listings, published dishes on live menus, of active merchants with a market.
 *
 * @param id the listing, dish or merchant id (ULIDs never collide across tables)
 * @param kind one of {@code service}, {@code product}, {@code food}, {@code merchant}
 * @param market the merchant's province (AB, BC …): search only ever shows one market
 * @param categoryPath category ids from the root group to the leaf (food: the kitchen's cuisines)
 * @param trustRank 1 registered · 2 trusted · 3 master (boosts; {@code trustTier} is the code)
 * @param deliveryCutoffMinute minutes after midnight (Edmonton) of the same-day pooled run cut-off
 * @param inStock products: stock left (null for other kinds)
 * @param soldOutOn dishes: the Edmonton date they are sold out for
 * @param openHours minute-of-week ranges (Monday 00:00 Edmonton = 0, {@code lt} exclusive)
 * @param pausedUntil kitchens: new orders paused until then
 * @param imageKey opaque image reference: {@code media:<catalogue.media id>} or {@code object:<storage key>}
 * @param suggest completion inputs (the name and its later words) and weight
 * @param suggestCategory completion inputs of the category names
 */
@Builder(toBuilder = true)
public record ListingDocument(
        String id,
        String kind,
        String market,
        String merchantId,
        String merchantName,
        String merchantType,
        String merchantStatus,
        @Nullable String merchantSlug,
        String name,
        @Nullable String description,
        @Nullable String keywords,
        @Nullable String categoryId,
        List<String> categoryPath,
        @Nullable String categoryRoot,
        List<String> categoryNames,
        @Nullable Long priceCents,
        @Nullable String pricingMode,
        @Nullable Double rating,
        int reviewCount,
        @Nullable String trustTier,
        int trustRank,
        @Nullable Integer qualityScore,
        String vetting,
        String status,
        boolean instantBook,
        List<String> fulfilment,
        @Nullable Integer deliveryCutoffMinute,
        @Nullable Boolean inStock,
        @Nullable LocalDate soldOutOn,
        List<MinuteRange> openHours,
        @Nullable Instant pausedUntil,
        @Nullable Integer prepMinutes,
        List<String> allergens,
        List<String> dietary,
        @Nullable GeoPoint location,
        @Nullable Double serviceRadiusKm,
        @Nullable String imageKey,
        int sales30d,
        Instant updatedAt,
        Completion suggest,
        @Nullable Completion suggestCategory) {

    public static final String SERVICE = "service";
    public static final String PRODUCT = "product";
    public static final String FOOD = "food";
    public static final String MERCHANT = "merchant";

    /** An {@code integer_range}: {@code gte} inclusive, {@code lt} exclusive. */
    public record MinuteRange(int gte, int lt) {}

    public record GeoPoint(double lat, double lon) {}

    /** Completion field value: every input can start a suggestion; heavier ones come first. */
    public record Completion(List<String> input, int weight) {}
}
