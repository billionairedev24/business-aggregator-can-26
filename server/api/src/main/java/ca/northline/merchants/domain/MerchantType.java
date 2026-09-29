package ca.northline.merchants.domain;

import ca.northline.shared.CodedEnum;
import java.util.List;

/**
 * {@code merchants.merchants.type}. Carries the per-type rules of validation-rules.md › Business step (category
 * limit, which taxonomy roots apply) and the storefront page kind (storefront-sections.json {@code $comment}).
 */
public enum MerchantType implements CodedEnum {
    PROVIDER(10, List.of(CategoryRoot.SERVICE), PageKind.BUSINESS_PAGE),
    SELLER(5, List.of(CategoryRoot.SHOP), PageKind.STORE),
    KITCHEN(3, List.of(CategoryRoot.FOOD), PageKind.MENU_PAGE),
    BOTH(10, List.of(CategoryRoot.SERVICE, CategoryRoot.SHOP), PageKind.BUSINESS_PAGE);

    private final int categoryLimit;

    @SuppressWarnings("ImmutableEnumChecker") // List.of is unmodifiable
    private final List<CategoryRoot> roots;

    private final PageKind pageKind;

    MerchantType(int categoryLimit, List<CategoryRoot> roots, PageKind pageKind) {
        this.categoryLimit = categoryLimit;
        this.roots = roots;
        this.pageKind = pageKind;
    }

    /** Most categories a merchant of this type may hold (also enforced by trigger {@code trg_category_limit}). */
    public int categoryLimit() {
        return categoryLimit;
    }

    /** Taxonomy roots ({@code catalogue.categories.root}) offered in the Business step. */
    public List<CategoryRoot> roots() {
        return roots;
    }

    public PageKind pageKind() {
        return pageKind;
    }
}
