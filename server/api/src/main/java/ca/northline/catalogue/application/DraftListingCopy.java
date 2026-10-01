package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.ListingKind;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * S-131: a listing title, description and bullets in English and French drafted from the listing's facts. Always a
 * draft: nothing is saved, the person edits it in the editor, and the listing is vetted as usual when submitted.
 */
public interface DraftListingCopy {

    /** What the editor knows so far (nothing is read from other businesses). */
    record Facts(
            ListingKind kind,
            @Nullable String name,
            @Nullable String categoryId,
            @Nullable String brand,
            Map<String, String> attributes,
            @Nullable String included,
            @Nullable Integer durationMin,
            @Nullable String notes) {
        public Facts {
            attributes = Map.copyOf(attributes);
        }
    }

    record Copy(String title, String description, List<String> bullets) {}

    /** {@code aiAssisted} is always true: the Studio labels the draft so the person knows to review it. */
    record Draft(Copy en, Copy fr, boolean aiAssisted, String model, String prompt) {}

    Draft draft(String merchantId, String userId, Facts facts);
}
