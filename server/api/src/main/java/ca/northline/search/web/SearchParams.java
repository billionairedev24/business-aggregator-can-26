package ca.northline.search.web;

import ca.northline.search.application.SearchSettings;
import ca.northline.search.domain.Coordinates;
import ca.northline.search.domain.SearchKind;
import ca.northline.search.domain.SearchMessages;
import ca.northline.search.domain.SearchQuery;
import ca.northline.search.domain.SearchSort;
import ca.northline.search.domain.SuggestQuery;
import ca.northline.search.domain.TrustTier;
import ca.northline.searchindex.SearchLanguage;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import io.swagger.v3.oas.annotations.Parameter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Query parameters of {@code GET /api/v1/search} (and, for {@code q}, {@code market}, {@code lang}, {@code kind},
 * {@code size}, of {@code /suggest}). Lists repeat the parameter or separate values with commas
 * ({@code kind=service,food}). Codes are checked here so a wrong one gets a 422 message rather than a 400.
 */
record SearchParams(
        @Parameter(description = "What was typed; blank = browse by the filters") @Nullable
        String q,

        @Parameter(
                description =
                        "Province or territory code of the person's location (default: the configured SEARCH_DEFAULT_MARKET)")
        @Nullable
        String market,

        @Parameter(description = "en | fr — picks listings_en or listings_fr (default: Accept-Language)") @Nullable
        String lang,

        @Parameter(description = "service, product, food, merchant (several allowed)") @Nullable
        List<String> kind,

        @Parameter(description = "A category id at any level (group or leaf)") @Nullable
        String category,

        @Parameter(description = "Lowest price, cents") @Nullable
        Long minPrice,

        @Parameter(description = "Highest price, cents") @Nullable
        Long maxPrice,

        @Parameter(description = "Lowest average rating, 1–5") @Nullable
        Double minRating,

        @Parameter(description = "registered, trusted, master (several allowed)") @Nullable
        List<String> tier,

        @Parameter(description = "Services that can be booked at once") @Nullable
        Boolean instantBook,

        @Parameter(description = "Open at this minute (the market's local time), not paused, not sold out today")
        @Nullable
        Boolean openNow,

        @Parameter(description = "tonight = on tonight's pooled run (before the seller's cut-off, in stock)") @Nullable
        String delivery,

        @Parameter(description = "Dietary tags every result has: halal, vegan, gluten_free …") @Nullable
        List<String> dietary,

        @Parameter(description = "Allergens no result contains (Health Canada codes: peanuts, tree_nuts …)") @Nullable
        List<String> allergenFree,

        @Parameter(description = "The person's latitude (with lng): distances, nearness boost, distance sort/filter")
        @Nullable
        Double lat,

        @Parameter(description = "The person's longitude (with lat)") @Nullable
        Double lng,

        @Parameter(description = "Only results within this many km of lat/lng (1–100)") @Nullable
        Double radiusKm,

        @Parameter(description = "relevance (default), distance, price_asc, price_desc, rating") @Nullable
        String sort,

        @Parameter(description = "Results per page, 1–50 (default 24; suggestions 1–10, default 6)") @Nullable
        Integer size,

        @Parameter(description = "The `next` token of the previous page") @Nullable
        String after) {

    SearchQuery toQuery(SearchSettings settings, @Nullable String acceptLanguage) {
        var problems = new ArrayList<Violation>();
        var searched = market(settings, problems);
        var kinds = codes(SearchKind.class, kind, "kind", SearchMessages.KIND, problems);
        var tiers = codes(TrustTier.class, tier, "tier", SearchMessages.TIER, problems);
        var order = sort == null || sort.isBlank()
                ? SearchSort.RELEVANCE
                : code(SearchSort.class, sort, "sort", SearchMessages.SORT, problems);
        if (delivery != null && !delivery.isBlank() && !delivery.equals("tonight")) {
            problems.add(new Violation("delivery", "format", "Use delivery=tonight or leave it out."));
        }
        var near = coordinates(problems);
        if (!problems.isEmpty()) {
            throw new RuleViolation(List.copyOf(problems));
        }
        return new SearchQuery(
                q,
                searched,
                language(acceptLanguage),
                kinds,
                blankToNull(category),
                minPrice,
                maxPrice,
                minRating,
                tiers,
                Boolean.TRUE.equals(instantBook),
                Boolean.TRUE.equals(openNow),
                "tonight".equals(delivery),
                lowercase(dietary),
                lowercase(allergenFree),
                near,
                radiusKm,
                Objects.requireNonNull(order),
                size == null ? SearchQuery.DEFAULT_SIZE : size,
                blankToNull(after));
    }

    SuggestQuery toSuggest(SearchSettings settings, @Nullable String acceptLanguage) {
        var problems = new ArrayList<Violation>();
        var searched = market(settings, problems);
        var kinds = codes(SearchKind.class, kind, "kind", SearchMessages.KIND, problems);
        if (!problems.isEmpty()) {
            throw new RuleViolation(List.copyOf(problems));
        }
        return new SuggestQuery(
                Objects.requireNonNullElse(q, ""),
                searched,
                language(acceptLanguage),
                kinds,
                size == null ? SuggestQuery.DEFAULT_SIZE : size);
    }

    /** The requested market, else the configured default; a well-formed code search doesn't serve is refused. */
    private String market(SearchSettings settings, List<Violation> problems) {
        var code = market == null || market.isBlank()
                ? settings.defaultMarket()
                : market.strip().toUpperCase(Locale.ROOT);
        if (code == null) {
            problems.add(new Violation("market", "required", SearchMessages.MARKET_REQUIRED));
            return "";
        }
        if (SearchQuery.MARKET.matcher(code).matches() && !settings.serves(code)) {
            problems.add(new Violation("market", "unsupported", SearchMessages.marketNotServed(code)));
        }
        return code;
    }

    private SearchLanguage language(@Nullable String acceptLanguage) {
        return SearchLanguage.of(lang != null && !lang.isBlank() ? lang : acceptLanguage);
    }

    private @Nullable Coordinates coordinates(List<Violation> problems) {
        if (lat == null && lng == null) {
            return null;
        }
        if (lat == null || lng == null) {
            problems.add(new Violation(lat == null ? "lat" : "lng", "required", SearchMessages.LOCATION_PAIR));
            return null;
        }
        try {
            return new Coordinates(lat, lng);
        } catch (RuleViolation e) {
            problems.addAll(e.getViolations());
            return null;
        }
    }

    private static <E extends Enum<E> & CodedEnum> Set<E> codes(
            Class<E> type, @Nullable List<String> values, String field, String message, List<Violation> problems) {
        var out = EnumSet.noneOf(type);
        for (var value : split(values)) {
            var parsed = code(type, value, field, message, problems);
            if (parsed != null) {
                out.add(parsed);
            }
        }
        return out;
    }

    private static <E extends Enum<E> & CodedEnum> @Nullable E code(
            Class<E> type, String value, String field, String message, List<Violation> problems) {
        for (var constant : type.getEnumConstants()) {
            if (constant.code().equals(value.strip().toLowerCase(Locale.ROOT))) {
                return constant;
            }
        }
        if (problems.stream().noneMatch(p -> p.field().equals(field))) {
            problems.add(new Violation(field, "format", message));
        }
        return null;
    }

    private static List<String> split(@Nullable List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .flatMap(v -> java.util.Arrays.stream(v.split(",")))
                .map(String::strip)
                .filter(v -> !v.isEmpty())
                .toList();
    }

    private static Set<String> lowercase(@Nullable List<String> values) {
        var out = new HashSet<String>();
        split(values).forEach(v -> out.add(v.toLowerCase(Locale.ROOT)));
        return out;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
