package ca.northline.merchants.api;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Businesses' categories as the platform console manages them (S-94): how many categories each type of business may
 * hold, who holds each category, and the categories businesses suggested in onboarding (free text, synthetic id
 * {@code suggested:<slug>}) for staff to approve as a new category or merge into an existing one. Implemented by the
 * merchants module, which owns {@code merchants.merchant_categories} and {@code merchants.category_limits}.
 */
public interface MerchantCategories {

    /** One row per business type (provider, seller, both, kitchen). */
    List<Limit> limits();

    /** Sets the limit of a type ({@code Conflict} when unchanged is not raised: the same value is a no-op). */
    Limit setLimit(String merchantType, int max, String actorId);

    /** By category id: active businesses holding it (approved or requested) and their provinces. */
    java.util.Map<String, Usage> usage();

    /** Suggestions still waiting (status requested), grouped by suggestion id, most businesses first. */
    List<Suggestion> suggestions();

    /**
     * Moves every business holding {@code suggestionId} to {@code categoryId}: approved, or requested when the category
     * is regulated (its licence is checked as for any regulated category). A business that already holds the category
     * just loses the suggestion. 404 when nobody holds the suggestion.
     */
    Resolution resolve(String suggestionId, String categoryId, boolean regulated, String actorId);

    /** Most categories per type: provider 10, seller 5, both 10, kitchen 3 unless staff changed it. */
    record Limit(String merchantType, int max, long businessesAbove, Instant updatedAt, String updatedBy) {}

    record Usage(long businesses, Set<String> provinces) {

        public Usage {
            provinces = Set.copyOf(provinces);
        }
    }

    record Suggestion(String id, String name, List<Business> businesses) {

        public Suggestion {
            businesses = List.copyOf(businesses);
        }
    }

    record Business(
            String id,
            String name,
            String type,
            @Nullable String province,
            @Nullable String status) {}

    record Resolution(String suggestionId, String categoryId, List<String> moved, List<String> alreadyHeld) {

        public Resolution {
            moved = List.copyOf(moved);
            alreadyHeld = List.copyOf(alreadyHeld);
        }
    }
}
