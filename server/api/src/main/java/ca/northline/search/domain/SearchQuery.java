package ca.northline.search.domain;

import ca.northline.searchindex.SearchLanguage;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One search: text, the market and language to search in, filters, where the person is, order and page. Every rule is
 * checked at once (one error per field, 422). Only live, approved listings of active merchants are ever in the index;
 * the market filter keeps a search inside the person's province.
 *
 * @param text what was typed (blank = browse everything that matches the filters)
 * @param market province code of the person's location (AB, BC, ON, QC)
 * @param categoryId a category id at any level (group or leaf)
 * @param openNow open at this minute (weekly hours), not paused, not sold out today
 * @param deliveryTonight on tonight's pooled run: pooled delivery, before the seller's same-day cut-off, in stock
 * @param dietary every tag must be present (halal, vegan …)
 * @param allergenFree none of these allergens (Health Canada codes)
 * @param near the person's location: distance on every result, nearness boost, distance sort and filter
 * @param radiusKm only results within this distance of {@code near}
 * @param after the {@code next} token of the previous page (search_after); opaque
 */
public record SearchQuery(
        @Nullable String text,
        String market,
        SearchLanguage language,
        Set<SearchKind> kinds,
        @Nullable String categoryId,
        @Nullable Long minPriceCents,
        @Nullable Long maxPriceCents,
        @Nullable Double minRating,
        Set<TrustTier> tiers,
        boolean instantBook,
        boolean openNow,
        boolean deliveryTonight,
        Set<String> dietary,
        Set<String> allergenFree,
        @Nullable Coordinates near,
        @Nullable Double radiusKm,
        SearchSort sort,
        int size,
        @Nullable String after) {

    public static final int DEFAULT_SIZE = 24;
    static final Pattern MARKET = Pattern.compile("AB|BC|ON|QC");

    public SearchQuery {
        text = text == null || text.isBlank() ? null : text.strip();
        kinds = Set.copyOf(kinds);
        tiers = Set.copyOf(tiers);
        dietary = Set.copyOf(dietary);
        allergenFree = Set.copyOf(allergenFree);
        var problems = new ArrayList<Violation>();
        if (text != null && text.length() > SearchMessages.MAX_QUERY) {
            problems.add(new Violation("q", "length", SearchMessages.QUERY_TOO_LONG));
        }
        if (!MARKET.matcher(market).matches()) {
            problems.add(new Violation("market", "format", SearchMessages.MARKET));
        }
        if ((minPriceCents != null && minPriceCents < 0) || (maxPriceCents != null && maxPriceCents < 0)) {
            problems.add(new Violation(
                    minPriceCents != null && minPriceCents < 0 ? "minPrice" : "maxPrice",
                    "range",
                    SearchMessages.PRICE));
        } else if (minPriceCents != null && maxPriceCents != null && minPriceCents > maxPriceCents) {
            problems.add(new Violation("minPrice", "range", SearchMessages.PRICE_ORDER));
        }
        if (minRating != null && (minRating < 1 || minRating > 5)) {
            problems.add(new Violation("minRating", "range", SearchMessages.RATING));
        }
        if (radiusKm != null) {
            if (radiusKm < 1 || radiusKm > SearchMessages.MAX_RADIUS_KM) {
                problems.add(new Violation("radiusKm", "range", SearchMessages.RADIUS));
            } else if (near == null) {
                problems.add(new Violation("radiusKm", "required", SearchMessages.RADIUS_NEEDS_LOCATION));
            }
        }
        if (sort == SearchSort.DISTANCE && near == null) {
            problems.add(new Violation("sort", "required", SearchMessages.DISTANCE_NEEDS_LOCATION));
        }
        if (size < 1 || size > SearchMessages.MAX_SIZE) {
            problems.add(new Violation("size", "range", SearchMessages.SIZE));
        }
        if (!problems.isEmpty()) {
            throw new RuleViolation(List.copyOf(problems));
        }
    }
}
