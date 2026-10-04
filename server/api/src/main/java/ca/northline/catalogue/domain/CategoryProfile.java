package ca.northline.catalogue.domain;

import ca.northline.region.api.AgeClass;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a category decides for a listing: where it sits (root, leaf), required attributes and variation themes, the
 * licence it needs (regulated registry), whether it is banned, and the median approved price used by vetting.
 *
 * @param root {@code service} | {@code shop} | {@code food}
 * @param perishable food & grocery leaves (final sale allowed)
 * @param ageClass the age-restriction class the category carries (its own or its group's), or null — listings in it
 *     need the business's licence for the class and an age-verified customer
 */
public record CategoryProfile(
        String id,
        @Nullable String parentId,
        String root,
        String name,
        boolean leaf,
        @Nullable String regulatedRegistry,
        boolean banned,
        boolean perishable,
        List<AttributeSpec> attributes,
        List<VariantTheme> variantThemes,
        @Nullable Long medianPriceCents,
        @Nullable AgeClass ageClass) {

    public CategoryProfile {
        attributes = List.copyOf(attributes);
        variantThemes = List.copyOf(variantThemes);
    }

    public boolean isShop() {
        return "shop".equals(root);
    }

    public boolean isService() {
        return "service".equals(root);
    }

    /** One category-driven attribute ("Length", "Position"). */
    public record AttributeSpec(String key, String label, List<String> options, boolean required) {
        public AttributeSpec {
            options = List.copyOf(options);
        }
    }
}
