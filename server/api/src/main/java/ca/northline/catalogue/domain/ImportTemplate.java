package ca.northline.catalogue.domain;

import ca.northline.shared.CodedEnum;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Bulk-upload templates ({@code catalogue.imports.template}). Columns are the header row of the downloadable .xlsx;
 * attribute columns are the category's attribute keys in snake_case ({@code partType} → {@code part_type}).
 * Rows sharing a {@code parent_sku} become variants of one listing.
 */
public enum ImportTemplate implements CodedEnum {
    AUTO_PARTS(
            ListingKind.PRODUCT,
            "shop.hardware-and-auto.auto-parts",
            List.of(
                    "sku",
                    "title",
                    "gtin",
                    "brand",
                    "mpn",
                    "category_id",
                    "price",
                    "stock",
                    "part_type",
                    "length",
                    "position")),
    GROCERIES(
            ListingKind.PRODUCT,
            "shop.food-and-grocery.groceries",
            List.of("sku", "title", "gtin", "brand", "category_id", "price", "stock", "volume", "storage")),
    CLOTHING(
            ListingKind.PRODUCT,
            "shop.apparel.clothing",
            List.of(
                    "parent_sku",
                    "sku",
                    "title",
                    "gtin",
                    "brand",
                    "category_id",
                    "price",
                    "stock",
                    "department",
                    "material",
                    "size",
                    "colour")),
    SERVICES(
            ListingKind.SERVICE,
            null,
            List.of(
                    "sku",
                    "name",
                    "category_id",
                    "pricing_mode",
                    "price",
                    "duration_min",
                    "buffer_min",
                    "included",
                    "instant_book")),
    /** Updates only: rows must match an existing SKU. */
    PRICE_STOCK(null, null, List.of("sku", "price", "stock"));

    private final @Nullable ListingKind kind;
    private final @Nullable String defaultCategoryId;

    @SuppressWarnings("ImmutableEnumChecker") // List.of is unmodifiable
    private final List<String> columns;

    ImportTemplate(@Nullable ListingKind kind, @Nullable String defaultCategoryId, List<String> columns) {
        this.kind = kind;
        this.defaultCategoryId = defaultCategoryId;
        this.columns = columns;
    }

    /** Null for the price & stock template, which updates either kind. */
    public @Nullable ListingKind kind() {
        return kind;
    }

    public @Nullable String defaultCategoryId() {
        return defaultCategoryId;
    }

    public List<String> columns() {
        return columns;
    }
}
