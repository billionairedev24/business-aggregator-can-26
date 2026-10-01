package ca.northline.search.application;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * S-132: what a person typed, turned into the search API's own filters (S-44 {@code GET /api/v1/search} parameters).
 * The consumer app shows the filters as chips the person can remove, then searches with them; the model never runs the
 * search or sees results.
 */
public interface InterpretSearch {

    /**
     * Parameters of {@code GET /api/v1/search}, already checked against its rules (codes, ranges): money in cents,
     * {@code delivery} = {@code tonight} or null, {@code radiusKm} and {@code sort=distance} only when the person shared a
     * location. {@code explanation} is one sentence in the person's language for the "Searching for …" line.
     */
    record Interpretation(
            @Nullable String q,
            List<String> kind,
            @Nullable Long minPrice,
            @Nullable Long maxPrice,
            @Nullable Double minRating,
            List<String> tier,
            @Nullable Boolean instantBook,
            @Nullable Boolean openNow,
            @Nullable String delivery,
            List<String> dietary,
            List<String> allergenFree,
            @Nullable Double radiusKm,
            @Nullable String sort,
            String explanation,
            boolean aiAssisted,
            String model) {}

    /** @param visitor who pays the AI budget: a user id, or a hashed guest / address key */
    Interpretation interpret(String visitor, String text, String lang, boolean hasLocation);
}
