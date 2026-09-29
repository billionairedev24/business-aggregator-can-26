package ca.northline.catalogue.domain;

/**
 * Validation messages for listings. validation-rules.md has no catalogue section, so these were chosen by the catalogue
 * workstream (docs/DECISIONS.md › Catalogue); the web client uses the same strings. Bean Validation annotations and
 * domain checks both reference these constants.
 */
public final class ListingMessages {
    private ListingMessages() {}

    public static final int TITLE_MAX = 80;
    public static final int NAME_MAX = 80;
    public static final int SKU_MAX = 40;
    public static final int BULLETS_MAX = 5;
    public static final int BULLET_MAX = 250;
    public static final int KEYWORDS_MAX = 250;
    public static final int DESCRIPTION_MAX = 4000;
    public static final int IMAGES_MAX = 9; // main + up to 8
    public static final long PRICE_MAX_CENTS = 10_000_000_00L;

    // product · identity
    public static final String TITLE_REQUIRED = "Enter a product title.";
    public static final String TITLE_TOO_LONG = "At most 80 characters.";
    public static final String TITLE_PROMO = "Leave out promo words like sale, free or best.";
    public static final String CATEGORY_REQUIRED = "Choose a category.";
    public static final String CATEGORY_LEAF = "Choose a category down to the last level.";
    public static final String CATEGORY_WRONG_ROOT = "Category not allowed in Shop";
    public static final String CATEGORY_WRONG_ROOT_SERVICE = "Choose a service category.";
    public static final String ATTRIBUTE_REQUIRED = "Choose %s.";
    public static final String BULLETS_TOO_MANY = "Up to 5 bullet points.";
    public static final String BULLET_TOO_LONG = "At most 250 characters.";
    public static final String DESCRIPTION_TOO_LONG = "At most 4000 characters.";
    public static final String GTIN_REQUIRED = "Enter the GTIN, or choose None (handmade / local).";
    public static final String GTIN_FORMAT = "GTIN must be 8, 12, 13 or 14 digits.";
    public static final String GTIN_CHECK_DIGIT = "GTIN check digit invalid";

    // product · variants
    public static final String VARIANTS_REQUIRED = "Add at least one variant.";
    public static final String VARIANT_VALUE_REQUIRED = "Name this variant.";
    public static final String VARIANT_DUPLICATE = "Each variant needs its own value.";
    public static final String SKU_REQUIRED = "Enter a SKU.";
    public static final String SKU_TOO_LONG = "At most 40 characters.";
    public static final String SKU_TAKEN = "That SKU is already used by another listing.";
    public static final String SKU_DUPLICATE = "Each variant needs its own SKU.";

    // product · images
    public static final String IMAGES_REQUIRED = "Add a main image on white, at least 1000 px.";
    public static final String IMAGES_TOO_MANY = "Main image plus up to 8 more.";
    public static final String IMAGE_UNKNOWN = "That image is no longer available — upload it again.";
    public static final String IMAGE_TYPE = "Use a JPG or PNG image.";
    public static final String IMAGE_TOO_SMALL = "Images must be at least 1000 px on the longest side.";
    public static final String IMAGE_TOO_LARGE = "Images must be 15 MB or smaller.";
    public static final String IMAGE_REQUIRED = "Choose an image to upload.";
    public static final int IMAGE_MIN_PX = 1000;
    public static final long IMAGE_MAX_BYTES = 15L * 1024 * 1024;

    // offer
    public static final String PRICE_REQUIRED = "Enter a price.";
    public static final String PRICE_POSITIVE = "Enter a price above $0.";
    public static final String COMPARE_AT_HIGHER = "Compare-at must be higher than your price.";
    public static final String COST_NEGATIVE = "Cost can't be negative.";
    public static final String STOCK_REQUIRED = "Enter stock on hand.";
    public static final String STOCK_NEGATIVE = "Stock can't be negative.";
    public static final String LOW_STOCK_NEGATIVE = "Alert level can't be negative.";
    public static final String FULFILMENT_REQUIRED = "Choose at least one fulfilment option.";
    public static final String FINAL_SALE_PERISHABLE = "Final sale is allowed only for perishables.";

    // compliance
    public static final String ORIGIN_REQUIRED = "Choose the country of origin.";
    public static final String RESTRICTED_REQUIRED = "Confirm this is not a restricted product.";
    public static final String BILINGUAL_REQUIRED = "Confirm bilingual labelling.";
    public static final String KEYWORDS_TOO_LONG = "At most 250 characters.";

    // service
    public static final String NAME_REQUIRED = "Enter a service name.";
    public static final String NAME_TOO_LONG = "At most 80 characters.";
    public static final String PRICING_REQUIRED = "Choose how you price this service.";
    public static final String DURATION_REQUIRED = "Choose a duration.";
    public static final String DURATION_RANGE = "Choose a duration between 15 minutes and 12 hours.";
    public static final String BUFFER_RANGE = "Buffer must be between 0 and 120 minutes.";
    public static final String INCLUDED_REQUIRED = "Describe what's included.";
    public static final String INCLUDED_TOO_LONG = "At most 2000 characters.";
    public static final int INCLUDED_MAX = 2000;

    // lifecycle
    public static final String NOT_SUBMITTABLE = "Only drafts and rejected listings can be submitted for vetting.";
    public static final String DRAFT_CANNOT_PUBLISH = "Submit this listing for vetting before publishing it.";
}
