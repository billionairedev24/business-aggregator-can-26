package ca.northline.catalogue.domain;

import static ca.northline.catalogue.domain.ListingMessages.*;

import ca.northline.shared.RuleViolation.Violation;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What the service editor edits: name, category, pricing (fixed / quote / hourly), price incl. travel, duration,
 * buffer after, what's included, instant book. {@code priceCents} is null for quote-priced services.
 */
public record ServiceDetails(
        String name,
        @Nullable String categoryId,
        PricingMode pricingMode,
        @Nullable Long priceCents,
        int durationMin,
        int bufferMin,
        @Nullable String included,
        boolean instantBook,
        @Nullable String sku) {

    public ServiceDetails {
        name = name.strip();
        included = included == null || included.isBlank() ? null : included.strip();
        sku = sku == null || sku.isBlank() ? null : sku.strip();
        if (pricingMode == PricingMode.QUOTE) {
            priceCents = null;
        }
    }

    public ServiceDetails withSku(String generated) {
        return new ServiceDetails(
                name, categoryId, pricingMode, priceCents, durationMin, bufferMin, included, instantBook, generated);
    }

    /** Rules checked on every save (draft included) beyond the request shape. */
    public List<Violation> validate(@Nullable CategoryProfile category) {
        var out = new ArrayList<Violation>();
        if (name.isEmpty()) {
            out.add(new Violation("name", "required", NAME_REQUIRED));
        }
        if (pricingMode != PricingMode.QUOTE && (priceCents == null || priceCents <= 0)) {
            out.add(new Violation(
                    "priceCents",
                    priceCents == null ? "required" : "range",
                    priceCents == null ? PRICE_REQUIRED : PRICE_POSITIVE));
        }
        if (categoryId != null) {
            if (category == null) {
                out.add(new Violation("categoryId", "required", CATEGORY_REQUIRED));
            } else if (!category.isService()) {
                out.add(new Violation("categoryId", "category", CATEGORY_WRONG_ROOT_SERVICE));
            } else if (!category.leaf()) {
                out.add(new Violation("categoryId", "leaf", CATEGORY_LEAF));
            }
        }
        return out;
    }

    /** Completeness: details (name, category) · pricing · schedule · what's included. */
    public Completeness completeness(@Nullable CategoryProfile category) {
        return new Completeness.Tally()
                .section(detailGaps(category))
                .section(
                        pricingMode != PricingMode.QUOTE && (priceCents == null || priceCents <= 0)
                                ? List.of(new Violation("priceCents", "required", PRICE_REQUIRED))
                                : List.of())
                .section(
                        durationMin <= 0
                                ? List.of(new Violation("durationMin", "required", DURATION_REQUIRED))
                                : List.of())
                .section(
                        included == null
                                ? List.of(new Violation("included", "required", INCLUDED_REQUIRED))
                                : List.of())
                .result();
    }

    private List<Violation> detailGaps(@Nullable CategoryProfile category) {
        var gaps = new ArrayList<Violation>();
        if (name.isEmpty()) {
            gaps.add(new Violation("name", "required", NAME_REQUIRED));
        }
        if (categoryId == null || category == null || !category.leaf()) {
            gaps.add(new Violation("categoryId", "required", CATEGORY_REQUIRED));
        }
        return gaps;
    }
}
