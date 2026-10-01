package ca.northline.catalogue.domain;

import ca.northline.shared.CodedEnum;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * S-39: the fields whose change sends an approved listing back to vetting ({@code revet_reasons}). They are the inputs
 * of the automated checks and what customers decide on:
 *
 * <ul>
 *   <li>{@link #PRICE}: the offer price, any variant's price, or a service's price or pricing mode.
 *   <li>{@link #CATEGORY}: the leaf category.
 *   <li>{@link #IMAGES}: the images customers see, meaning own images (added, removed or reordered, since the first is
 *       the main image) or the catalogue record's, and the switch between the two.
 * </ul>
 *
 * A bundle's contents (items and quantities) count as its {@link #PRICE} (S-65): they are what the price buys.
 *
 * Nothing else is material: title, description, attributes, stock, SKU, fulfilment and compliance fields. The same
 * rule applies whoever makes the change: the editor, a bulk price update, or a platform sync (S-35). The sync only
 * ever changes price and stock of a submitted listing.
 */
public enum MaterialField implements CodedEnum {
    PRICE,
    CATEGORY,
    IMAGES;

    /** What changed between two versions of a product, as customers would see it. */
    public static Set<MaterialField> between(ProductListing.Snapshot before, ProductListing.Snapshot after) {
        var changed = EnumSet.noneOf(MaterialField.class);
        if (!before.prices().equals(after.prices())) {
            changed.add(PRICE);
        }
        if (!Objects.equals(before.categoryId(), after.categoryId())) {
            changed.add(CATEGORY);
        }
        if (!before.images().equals(after.images())) {
            changed.add(IMAGES);
        }
        return changed;
    }

    /** What changed between two versions of a service. */
    public static Set<MaterialField> between(ServiceDetails before, ServiceDetails after) {
        var changed = EnumSet.noneOf(MaterialField.class);
        if (before.pricingMode() != after.pricingMode() || !Objects.equals(before.priceCents(), after.priceCents())) {
            changed.add(PRICE);
        }
        if (!Objects.equals(before.categoryId(), after.categoryId())) {
            changed.add(CATEGORY);
        }
        return changed;
    }

    static List<MaterialField> sorted(Set<MaterialField> fields) {
        return fields.stream().sorted().toList();
    }
}
