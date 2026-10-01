package ca.northline.catalogue.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The category taxonomy as the platform console edits it (S-94): categories with their names, booking type and licence
 * registry, the licensing bodies (regulators) by province, and which regulator a category answers to in each province.
 * The catalogue module owns {@code catalogue.categories}, {@code catalogue.regulators} and
 * {@code catalogue.category_regulators}; the console orchestrates (audit, merchants' suggestions). Input is validated
 * here: {@code RuleViolation} (422) for a field, {@code Conflict} (409) for a clash, {@code NotFound} (404).
 */
public interface TaxonomyAdmin {

    /** Every category (groups and leaves) with its live listings' median price, and every regulator. */
    Taxonomy taxonomy();

    Optional<Category> category(String id);

    /**
     * A new group ({@code parentId} null, under {@code root}) or leaf (under the group {@code parentId}). Its id is the
     * parent's (or root's) id plus a slug of the English name. 409 {@code category_exists} when that id is taken.
     */
    Category create(CategoryInput input, String actorId);

    /** Names, booking type, licence registry and vulnerable-sector check; the id, root and parent never change. */
    Change<Category> update(String id, CategoryInput input, String actorId);

    /**
     * The regulator of category {@code id} in {@code province}: a regulator code (of that province), {@link #NONE}
     * (not regulated there) or null (back to the category's default licence registry).
     */
    Change<Category> regulate(String id, String province, @Nullable String regulator, String actorId);

    Regulator createRegulator(RegulatorInput input, String actorId);

    Change<Regulator> updateRegulator(String code, RegulatorInput input, String actorId);

    /** {@link #regulate}: the category is not regulated in that province. */
    String NONE = "none";

    /** Messages (fr-CA in docs/spec/validation-messages.fr-CA.tsv). */
    String NAME_EN = "Enter the English name, 1 to 80 characters.";

    String NAME_FR = "Enter the French name, 1 to 80 characters.";
    String ROOT = "Choose services, shop or food.";
    String PARENT = "Choose a group of the same root.";
    String BOOKING_TYPE = "Choose visit, home, event, appointment or consult.";
    String REGISTRY = "The licence registry is at most 80 characters.";
    String REGULATOR_CODE = "Use 2 to 40 lowercase letters, digits, - or _.";
    String REGULATOR_NAME = "Enter the regulator's name, 1 to 80 characters.";
    String WEBSITE = "The website starts with https:// and is at most 200 characters.";
    String PROVINCE = "Choose a province from the list.";
    String REGULATOR_UNKNOWN = "Choose a regulator from the list.";
    String REGULATOR_ELSEWHERE = "That regulator is in another province.";
    String CATEGORY_EXISTS = "That group already has a category with this name.";
    String REGULATOR_EXISTS = "A regulator with this code already exists.";

    /**
     * @param parentId the group a leaf belongs to; null for a group
     * @param regulators where staff set it, the regulator by province (a rule without a regulator = not regulated there)
     * @param medianPriceCents the median price of the live listings in {@code priceMode} (the mode most of them use)
     * @param priceMode {@code fixed}, {@code hourly}, {@code quote} (every live listing is quoted) or null (none live)
     */
    record Category(
            String id,
            @Nullable String parentId,
            String root,
            String nameEn,
            @Nullable String nameFr,
            @Nullable String bookingType,
            @Nullable String regulatedRegistry,
            boolean requiresVsCheck,
            List<ProvinceRule> regulators,
            int liveListings,
            @Nullable Long medianPriceCents,
            @Nullable String priceMode,
            @Nullable Instant editedAt) {

        public Category {
            regulators = List.copyOf(regulators);
        }

        public boolean group() {
            return parentId == null;
        }
    }

    record ProvinceRule(String province, @Nullable String regulator) {}

    record Regulator(
            String code,
            String name,
            String province,
            @Nullable String website,
            Instant updatedAt) {}

    record Taxonomy(List<Category> categories, List<Regulator> regulators) {

        public Taxonomy {
            categories = List.copyOf(categories);
            regulators = List.copyOf(regulators);
        }
    }

    record CategoryInput(
            @Nullable String root,
            @Nullable String parentId,
            @Nullable String nameEn,
            @Nullable String nameFr,
            @Nullable String bookingType,
            @Nullable String regulatedRegistry,
            boolean requiresVsCheck) {}

    record RegulatorInput(
            @Nullable String code,
            @Nullable String name,
            @Nullable String province,
            @Nullable String website) {}

    /** What an edit changed, for the caller's audit entry. */
    record Change<T>(T before, T after) {}
}
