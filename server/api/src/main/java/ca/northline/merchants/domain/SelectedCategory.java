package ca.northline.merchants.domain;

import ca.northline.shared.CodedEnum;
import org.jspecify.annotations.Nullable;

/**
 * A category the merchant asked to list in ({@code merchants.merchant_categories}). Suggested categories (free text,
 * no taxonomy match) have no root or regulator and use the synthetic id {@code suggested:<slug>}.
 */
public record SelectedCategory(
        String id,
        String name,
        @Nullable CategoryRoot root,
        @Nullable String regulator,
        boolean suggested) {

    public static final String SUGGESTED_PREFIX = "suggested:";

    /** {@code merchants.merchant_categories.status}. */
    public enum Status implements CodedEnum {
        REQUESTED,
        APPROVED,
        REJECTED
    }

    public boolean regulated() {
        return regulator != null && !regulator.isBlank();
    }

    /** Regulated leaves start {@code requested} (registry check pending), as do suggestions; the rest are approved. */
    public Status initialStatus() {
        return suggested || regulated() ? Status.REQUESTED : Status.APPROVED;
    }
}
