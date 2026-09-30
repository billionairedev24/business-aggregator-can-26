package ca.northline.search.application;

import ca.northline.searchindex.ListingDocument;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One page of results.
 *
 * @param total matching results (counted exactly up to 10 000)
 * @param next token for the following page ({@code after}); null on the last page
 */
public record SearchResults(
        List<Hit> items,
        long total,
        Facets facets,
        @Nullable String next) {

    /**
     * A result and what is true of it now.
     *
     * @param distanceKm from the person's location, when both are known
     * @param openNow inside its weekly hours at this minute (the market's local time), not paused, not sold out today
     * @param soldOut a dish sold out today
     * @param onTonightsRun pooled delivery still before today's cut-off, in stock
     */
    public record Hit(
            ListingDocument document,
            @Nullable Double distanceKm,
            boolean openNow,
            boolean soldOut,
            boolean onTonightsRun) {}

    /** Counts per value over the whole result set (not only this page). */
    public record Facets(
            List<Bucket> kinds,
            List<Bucket> categories,
            List<Bucket> merchants,
            List<Bucket> tiers,
            List<Bucket> prices,
            List<Bucket> dietary) {

        public static final Facets NONE = new Facets(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** @param label a display name when the value is an id (category, merchant) or a range (price) */
    public record Bucket(String value, @Nullable String label, long count) {}

    public static SearchResults empty() {
        return new SearchResults(List.of(), 0, Facets.NONE, null);
    }
}
