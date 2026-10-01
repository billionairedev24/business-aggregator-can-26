package ca.northline.console.application;

import ca.northline.catalogue.api.TaxonomyAdmin.CategoryInput;
import ca.northline.catalogue.api.TaxonomyAdmin.ProvinceRule;
import ca.northline.catalogue.api.TaxonomyAdmin.RegulatorInput;
import ca.northline.merchants.api.MerchantCategories.Limit;
import ca.northline.merchants.api.MerchantCategories.Suggestion;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Platform console — catalogue taxonomy (S-94, design 03 {@code taxonomy}; admin only). The categories with their
 * regulators by province, sellers and live provinces, the regulators, the category limit per business type (V016's
 * trigger keeps enforcing it), and businesses' suggested categories to approve or merge. Every change is audited.
 */
public interface ManageTaxonomy {

    Screen screen();

    Row createCategory(CategoryInput input, Actor actor);

    Row updateCategory(String id, CategoryInput input, Actor actor);

    /** {@code regulator}: a regulator code, {@code none} (not regulated there) or null (the default registry). */
    Row regulate(String id, String province, @Nullable String regulator, Actor actor);

    RegulatorRow createRegulator(RegulatorInput input, Actor actor);

    RegulatorRow updateRegulator(String code, RegulatorInput input, Actor actor);

    Limit setLimit(String merchantType, int max, Actor actor);

    /** Creates the category (the suggestion's wording unless staff changed it) and moves every business to it. */
    Resolved approveSuggestion(String suggestionId, CategoryInput input, Actor actor);

    /** Moves every business holding the suggestion to an existing category. */
    Resolved mergeSuggestion(String suggestionId, String categoryId, Actor actor);

    /** "Merge into" needs a category: fr-CA in the catalogue. */
    String CATEGORY_REQUIRED = "Choose a category.";

    record Actor(String userId, String roles) {}

    /**
     * @param serviceCategories service leaves; {@code shopDepartments} shop groups (the design's headline)
     */
    record Screen(
            Instant asOf,
            long serviceCategories,
            long shopDepartments,
            List<Row> categories,
            List<RegulatorRow> regulators,
            List<Limit> limits,
            List<Suggestion> suggestions) {

        public Screen {
            categories = List.copyOf(categories);
            regulators = List.copyOf(regulators);
            limits = List.copyOf(limits);
            suggestions = List.copyOf(suggestions);
        }
    }

    /**
     * @param sellers active businesses holding the category
     * @param liveIn the provinces of those businesses
     */
    record Row(
            String id,
            @Nullable String parentId,
            String root,
            boolean group,
            String nameEn,
            @Nullable String nameFr,
            @Nullable String bookingType,
            @Nullable String regulatedRegistry,
            boolean requiresVsCheck,
            List<ProvinceRule> regulators,
            long sellers,
            List<String> liveIn,
            int liveListings,
            @Nullable Long medianPriceCents,
            @Nullable String priceMode) {

        public Row {
            regulators = List.copyOf(regulators);
            liveIn = List.copyOf(liveIn);
        }
    }

    record RegulatorRow(
            String code,
            String name,
            String province,
            @Nullable String website,
            long categories) {}

    record Resolved(Row category, int moved, int alreadyHeld) {}
}
