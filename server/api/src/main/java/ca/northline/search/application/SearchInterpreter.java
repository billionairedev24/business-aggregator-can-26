package ca.northline.search.application;

import ca.northline.ai.api.AiCompletions;
import ca.northline.ai.api.AiCompletions.Caller;
import ca.northline.ai.api.AiCompletions.Request;
import ca.northline.ai.api.AiFeature;
import ca.northline.ai.api.Prompts;
import ca.northline.search.domain.SearchKind;
import ca.northline.search.domain.SearchMessages;
import ca.northline.search.domain.SearchSort;
import ca.northline.search.domain.TrustTier;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.RuleViolation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * {@link InterpretSearch} over the AI port (prompt {@code search-filters}, light model). The model proposes; this class
 * keeps only values the search API accepts, so a bad answer can narrow nothing it shouldn't.
 */
@Service
@RequiredArgsConstructor
public class SearchInterpreter implements InterpretSearch {

    public static final String EMPTY = "Type what you're looking for.";
    public static final String TOO_LONG = "Keep it under 200 characters.";
    static final int MAX = 200;

    /** Dietary tags and Health Canada priority allergens the index knows (search.md § filters). */
    static final Set<String> DIETARY = Set.of("gluten_free", "vegan", "vegetarian", "halal");

    static final Set<String> ALLERGENS = Set.of(
            "eggs",
            "milk",
            "peanuts",
            "tree_nuts",
            "sesame",
            "soy",
            "wheat",
            "fish",
            "shellfish",
            "mustard",
            "sulphites");

    private final AiCompletions ai;
    private final Prompts prompts;

    @Override
    public Interpretation interpret(String visitor, String text, String lang, boolean hasLocation) {
        var typed = text.strip();
        if (typed.isEmpty()) {
            throw RuleViolation.of("text", "required", EMPTY);
        }
        if (typed.length() > MAX) {
            throw RuleViolation.of("text", "length", TOO_LONG);
        }
        var prompt = prompts.get("search-filters");
        var system = prompt.render(Map.of(
                "dietary",
                String.join(", ", DIETARY.stream().sorted().toList()),
                "allergens",
                String.join(", ", ALLERGENS.stream().sorted().toList()),
                "location",
                hasLocation
                        ? "the person shared their location"
                        : "the person did NOT share a location, so never set it",
                "language",
                "fr".equals(lang) ? "Canadian French" : "English"));
        var answer = ai.complete(Request.of(AiFeature.SEARCH_FILTERS, Caller.person(visitor), prompt, system, typed)
                .asJson()
                .withMaxTokens(300));
        var n = answer.json().orElseThrow(() -> new IllegalStateException("The model's filters weren't JSON."));
        var q = n.path("q").isString() && !n.path("q").asString().isBlank()
                ? cut(n.path("q").asString().strip(), 100)
                : null;
        var min = cents(n.path("minPriceDollars"));
        var max = cents(n.path("maxPriceDollars"));
        if (min != null && max != null && min > max) {
            var t = min;
            min = max;
            max = t;
        }
        var rating =
                n.path("minRating").isNumber() ? Math.clamp(n.path("minRating").asDouble(), 1.0, 5.0) : null;
        var radius = hasLocation && n.path("radiusKm").isNumber()
                ? Math.clamp(n.path("radiusKm").asDouble(), 1.0, SearchMessages.MAX_RADIUS_KM)
                : null;
        var sort = codes(n.path("sort"), SearchSort.class).stream()
                .filter(s -> hasLocation || !s.equals(SearchSort.DISTANCE.code()))
                .filter(s -> !s.equals(SearchSort.RELEVANCE.code()))
                .findFirst()
                .orElse(null);
        return new Interpretation(
                q,
                codes(n.path("kinds"), SearchKind.class),
                min,
                max,
                rating,
                codes(n.path("tiers"), TrustTier.class).stream()
                        .filter(t -> !t.equals(TrustTier.REGISTERED.code()))
                        .toList(),
                n.path("instantBook").asBoolean(false) ? Boolean.TRUE : null,
                n.path("openNow").asBoolean(false) ? Boolean.TRUE : null,
                n.path("deliveryTonight").asBoolean(false) ? "tonight" : null,
                allowed(n.path("dietary"), DIETARY),
                allowed(n.path("allergenFree"), ALLERGENS),
                radius,
                sort,
                cut(n.path("explanation").asString("").strip(), 160),
                true,
                answer.model());
    }

    private static @Nullable Long cents(JsonNode dollars) {
        return dollars.isNumber() && dollars.asDouble() >= 0 ? Math.round(dollars.asDouble() * 100) : null;
    }

    private static <E extends Enum<E> & CodedEnum> List<String> codes(JsonNode values, Class<E> type) {
        var known = Arrays.stream(type.getEnumConstants()).map(CodedEnum::code).collect(Collectors.toSet());
        return allowed(values, known);
    }

    private static List<String> allowed(JsonNode values, Set<String> known) {
        var out = new LinkedHashSet<String>();
        Iterable<JsonNode> list = values.isArray() ? values : values.isString() ? List.of(values) : List.of();
        for (var v : list) {
            var code = v.asString("")
                    .strip()
                    .toLowerCase(Locale.ROOT)
                    .replace('-', '_')
                    .replace(' ', '_');
            if (known.contains(code)) {
                out.add(code);
            }
        }
        return new ArrayList<>(out);
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max).strip();
    }
}
