package ca.northline.search.integration;

import ca.northline.search.application.SearchIndex;
import ca.northline.search.application.SearchMoment;
import ca.northline.search.application.SearchResults;
import ca.northline.search.application.SearchResults.Bucket;
import ca.northline.search.application.SearchResults.Facets;
import ca.northline.search.application.SearchResults.Hit;
import ca.northline.search.application.Suggestion;
import ca.northline.search.domain.Highlight;
import ca.northline.search.domain.SearchMessages;
import ca.northline.search.domain.SearchQuery;
import ca.northline.search.domain.SearchSort;
import ca.northline.search.domain.SuggestQuery;
import ca.northline.searchindex.ListingDocument;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.RuleViolation;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch.core.search.CompletionSuggestOption;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link SearchIndex} on Elasticsearch 9: the aliases {@code listings_en} / {@code listings_fr} (the query's language
 * picks one). Query DSL written as JSON (readable next to {@code deploy/search/listings.json}), results read back as
 * {@link ListingDocument}s.
 *
 * <ul>
 *   <li><b>Match:</b> the text against name (×4), merchant and category names (×2), keywords, description with the
 *       language's search analyzer (synonyms), or as a prefix of the name (search-as-you-type); every word must match.
 *   <li><b>Filters:</b> the market, approved + live + active (the index holds nothing else, the filters say so
 *       anyway), then the query's filters; time-dependent ones (open now, tonight's run, sold out today) against
 *       {@link SearchMoment}.
 *   <li><b>Score</b> (relevance): text score × (trust tier + rating + nearness) — {@link #scored}.
 *   <li><b>Pages:</b> {@code search_after} on the sort values plus the id; the token names its sort.
 *   <li><b>Facets</b> on the first page only.
 * </ul>
 */
public final class ElasticsearchSearchIndex implements SearchIndex {

    static final int MAX_TOTAL = 10_000;

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final ElasticsearchClient es;
    private final JsonMapper json;

    public ElasticsearchSearchIndex(ElasticsearchClient es, JsonMapper json) {
        this.es = es;
        this.json = json;
    }

    @Override
    public SearchResults search(SearchQuery query, SearchMoment now) {
        var body = body(query, now);
        try {
            var response = es.search(
                    s -> s.index(query.language().alias()).withJson(new StringReader(json.writeValueAsString(body))),
                    ListingDocument.class);
            var hits = new ArrayList<Hit>();
            List<FieldValue> lastSort = List.of();
            for (var hit : response.hits().hits()) {
                var doc = hit.source();
                if (doc == null) {
                    continue;
                }
                hits.add(hit(doc, query, now));
                lastSort = hit.sort();
            }
            var total = response.hits().total() == null
                    ? hits.size()
                    : response.hits().total().value();
            var next = hits.size() == query.size() && !lastSort.isEmpty() ? cursor(query.sort(), lastSort) : null;
            var facets = query.after() == null ? facets(response.aggregations()) : Facets.NONE;
            return new SearchResults(List.copyOf(hits), total, facets, next);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<Suggestion> suggest(SuggestQuery query) {
        var body = JSON.objectNode();
        body.put("size", 0);
        var suggest = body.putObject("suggest");
        var listings = suggest.putObject("listings");
        listings.put("prefix", query.prefix());
        var completion = listings.putObject("completion");
        // contexts of different kinds are OR-ed by Elasticsearch: the market is the context, kinds are filtered here
        completion
                .put("field", "suggest")
                .put("size", query.kinds().isEmpty() ? query.size() : query.size() * 4)
                .put("skip_duplicates", true);
        completion.putObject("contexts").putArray("market").add(query.market());
        var kinds = query.kinds().stream().map(CodedEnum::code).collect(java.util.stream.Collectors.toSet());
        var categoryRequest = suggest.putObject("categories");
        categoryRequest.put("prefix", query.prefix());
        categoryRequest
                .putObject("completion")
                .put("field", "suggestCategory")
                .put("size", Math.min(3, query.size()))
                .put("skip_duplicates", true)
                .putObject("contexts")
                .putArray("market")
                .add(query.market());
        try {
            var response = es.search(
                    s -> s.index(query.language().alias()).withJson(new StringReader(json.writeValueAsString(body))),
                    ListingDocument.class);
            var out = new ArrayList<Suggestion>();
            for (var option : options(response.suggest().get("listings"))) {
                var doc = option.source();
                if (doc == null || (!kinds.isEmpty() && !kinds.contains(doc.kind()))) {
                    continue;
                }
                out.add(new Suggestion(
                        doc.name(),
                        doc.kind(),
                        doc.id(),
                        doc.merchantId(),
                        doc.merchantName(),
                        doc.merchantType(),
                        doc.merchantSlug(),
                        doc.priceCents(),
                        doc.trustTier(),
                        doc.rating(),
                        Highlight.of(doc.name(), query.prefix())));
            }
            var categories = new ArrayList<Suggestion>();
            var seen = new java.util.HashSet<String>();
            for (var option : options(response.suggest().get("categories"))) {
                var doc = option.source();
                if (doc == null || doc.categoryId() == null) {
                    continue;
                }
                var name = categoryName(doc, option.text());
                if (seen.add(name.toLowerCase(Locale.ROOT))) {
                    categories.add(new Suggestion(
                            name,
                            "category",
                            doc.categoryId(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            Highlight.of(name, query.prefix())));
                }
            }
            // listings first; categories keep up to two of the places
            var forCategories = Math.min(Math.min(2, categories.size()), query.size());
            var listingsShown = Math.min(out.size(), query.size() - forCategories);
            var result = new ArrayList<>(out.subList(0, listingsShown));
            result.addAll(categories.subList(0, Math.min(categories.size(), query.size() - listingsShown)));
            return List.copyOf(result);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── the request ─────────────────────────────────────────────────────────────────────────────────────────────

    ObjectNode body(SearchQuery q, SearchMoment now) {
        var body = JSON.objectNode();
        body.put("size", q.size());
        body.put("track_total_hits", MAX_TOTAL);
        var bool = JSON.objectNode();
        var filter = bool.putArray("filter");
        filter.add(term("market", q.market()));
        filter.add(term("vetting", "approved"));
        filter.add(term("status", "live"));
        filter.add(term("merchantStatus", "active"));
        if (!q.kinds().isEmpty()) {
            filter.add(terms(
                    "kind", q.kinds().stream().map(CodedEnum::code).sorted().toList()));
        }
        if (q.categoryId() != null) {
            filter.add(term("categoryPath", q.categoryId()));
        }
        if (q.minPriceCents() != null || q.maxPriceCents() != null) {
            var range = JSON.objectNode();
            if (q.minPriceCents() != null) {
                range.put("gte", q.minPriceCents());
            }
            if (q.maxPriceCents() != null) {
                range.put("lte", q.maxPriceCents());
            }
            filter.add(JSON.objectNode().set("range", JSON.objectNode().set("priceCents", range)));
        }
        if (q.minRating() != null) {
            filter.add(JSON.objectNode()
                    .set(
                            "range",
                            JSON.objectNode().set("rating", JSON.objectNode().put("gte", q.minRating()))));
        }
        if (!q.tiers().isEmpty()) {
            filter.add(terms(
                    "trustTier",
                    q.tiers().stream().map(CodedEnum::code).sorted().toList()));
        }
        if (q.instantBook()) {
            filter.add(term("instantBook", true));
        }
        if (q.openNow()) {
            filter.add(term("openHours", now.minuteOfWeek()));
            var mustNot = bool.withArray("must_not");
            mustNot.add(JSON.objectNode()
                    .set(
                            "range",
                            JSON.objectNode()
                                    .set(
                                            "pausedUntil",
                                            JSON.objectNode()
                                                    .put("gt", now.instant().toString()))));
            mustNot.add(term("soldOutOn", now.today().toString()));
        }
        if (q.deliveryTonight()) {
            filter.add(term("fulfilment", "pooled"));
            filter.add(term("inStock", true));
            filter.add(JSON.objectNode()
                    .set(
                            "range",
                            JSON.objectNode()
                                    .set(
                                            "deliveryCutoffMinute",
                                            JSON.objectNode().put("gt", now.minuteOfDay()))));
        }
        q.dietary().stream().sorted().forEach(tag -> filter.add(term("dietary", tag)));
        if (!q.allergenFree().isEmpty()) {
            bool.withArray("must_not")
                    .add(terms("allergens", q.allergenFree().stream().sorted().toList()));
        }
        var near = q.near();
        if (near != null && q.radiusKm() != null) {
            var geo = JSON.objectNode();
            geo.put("distance", q.radiusKm() + "km");
            geo.set("location", point(near.lat(), near.lng()));
            filter.add(JSON.objectNode().set("geo_distance", geo));
        }
        if (q.sort() == SearchSort.DISTANCE) {
            filter.add(JSON.objectNode().set("exists", JSON.objectNode().put("field", "location")));
        }
        if (q.text() != null) {
            bool.withArray("must").add(text(q.text()));
        }
        body.set("query", scored(bool, q));
        body.set("sort", sort(q));
        if (q.sort() != SearchSort.RELEVANCE) {
            body.put("track_scores", false);
        }
        if (q.after() != null) {
            body.set("search_after", decode(q.sort(), q.after()));
        } else {
            body.set("aggs", aggregations());
        }
        return body;
    }

    private static ObjectNode text(String text) {
        var should = JSON.arrayNode();
        should.add(JSON.objectNode()
                .set(
                        "multi_match",
                        JSON.objectNode()
                                .put("query", text)
                                .put("type", "best_fields")
                                .put("operator", "and")
                                .set(
                                        "fields",
                                        array(
                                                "name^4",
                                                "merchantName^2",
                                                "categoryNames^2",
                                                "keywords^1.5",
                                                "description"))));
        should.add(JSON.objectNode()
                .set(
                        "multi_match",
                        JSON.objectNode()
                                .put("query", text)
                                .put("type", "cross_fields")
                                .put("operator", "and")
                                .put("boost", 0.5)
                                .set("fields", array("name^2", "merchantName", "categoryNames", "keywords"))));
        should.add(JSON.objectNode()
                .set(
                        "multi_match",
                        JSON.objectNode()
                                .put("query", text)
                                .put("type", "bool_prefix")
                                .put("operator", "and")
                                .set("fields", array("name.prefix^2", "name.prefix._2gram", "name.prefix._3gram"))));
        var bool = JSON.objectNode();
        bool.set("should", should);
        bool.put("minimum_should_match", 1);
        return JSON.objectNode().set("bool", bool);
    }

    /**
     * Relevance = the match × (trust tier + rating + nearness): tier 1.5 master · 1.2 trusted · 1.0 registered, rating
     * log10(2 + stars) (≈ 0.5–0.85), nearness up to 2 (Gauss: full within 1 km, half at 6 km) for results with a
     * location. Summed, so a good match farther away still ranks, just lower.
     */
    private static ObjectNode scored(ObjectNode bool, SearchQuery q) {
        var functions = JSON.arrayNode();
        functions.add(JSON.objectNode().put("weight", 1.5).set("filter", term("trustTier", "master")));
        functions.add(JSON.objectNode().put("weight", 1.2).set("filter", term("trustTier", "trusted")));
        functions.add(JSON.objectNode().put("weight", 1.0).set("filter", term("trustTier", "registered")));
        functions.add(JSON.objectNode()
                .set(
                        "field_value_factor",
                        JSON.objectNode()
                                .put("field", "rating")
                                .put("modifier", "log2p")
                                .put("missing", 3.5)));
        var near = q.near();
        if (near != null) {
            var decay = JSON.objectNode();
            decay.set("origin", point(near.lat(), near.lng()));
            decay.put("scale", "5km").put("offset", "1km").put("decay", 0.5);
            var gauss = JSON.objectNode().put("weight", 2);
            gauss.set(
                    "filter", JSON.objectNode().set("exists", JSON.objectNode().put("field", "location")));
            gauss.set("gauss", JSON.objectNode().set("location", decay));
            functions.add(gauss);
        }
        var score = JSON.objectNode();
        score.set("query", JSON.objectNode().set("bool", bool));
        score.set("functions", functions);
        score.put("score_mode", "sum").put("boost_mode", "multiply");
        return JSON.objectNode().set("function_score", score);
    }

    private static ArrayNode sort(SearchQuery q) {
        var sort = JSON.arrayNode();
        switch (q.sort()) {
            case RELEVANCE -> {
                sort.add(order("_score", "desc"));
                sort.add(order("trustRank", "desc"));
            }
            case DISTANCE -> {
                var near = Objects.requireNonNull(q.near());
                var geo = JSON.objectNode();
                geo.set("location", point(near.lat(), near.lng()));
                geo.put("order", "asc").put("unit", "km").put("distance_type", "arc");
                sort.add(JSON.objectNode().set("_geo_distance", geo));
            }
            case PRICE_ASC ->
                sort.add(JSON.objectNode()
                        .set("priceCents", JSON.objectNode().put("order", "asc").put("missing", "_last")));
            case PRICE_DESC ->
                sort.add(JSON.objectNode()
                        .set(
                                "priceCents",
                                JSON.objectNode().put("order", "desc").put("missing", "_last")));
            default -> { // RATING
                sort.add(JSON.objectNode()
                        .set("rating", JSON.objectNode().put("order", "desc").put("missing", 0)));
                sort.add(order("reviewCount", "desc"));
                sort.add(order("trustRank", "desc"));
            }
        }
        sort.add(order("id", "asc"));
        return sort;
    }

    private static ObjectNode order(String field, String direction) {
        return JSON.objectNode().set(field, JSON.objectNode().put("order", direction));
    }

    private static ObjectNode aggregations() {
        var aggs = JSON.objectNode();
        aggs.set("kinds", termsAgg("kind", 4));
        aggs.set("categories", withTop(termsAgg("categoryId", 20), "categoryNames"));
        aggs.set("merchants", withTop(termsAgg("merchantId", 20), "merchantName"));
        aggs.set("tiers", termsAgg("trustTier", 3));
        aggs.set("dietary", termsAgg("dietary", 20));
        var ranges = JSON.arrayNode();
        ranges.add(JSON.objectNode().put("key", "under_10").put("to", 1000));
        ranges.add(JSON.objectNode().put("key", "10_25").put("from", 1000).put("to", 2500));
        ranges.add(JSON.objectNode().put("key", "25_50").put("from", 2500).put("to", 5000));
        ranges.add(JSON.objectNode().put("key", "50_100").put("from", 5000).put("to", 10000));
        ranges.add(JSON.objectNode().put("key", "100_plus").put("from", 10000));
        var prices = JSON.objectNode();
        prices.put("field", "priceCents");
        prices.set("ranges", ranges);
        aggs.set("prices", JSON.objectNode().set("range", prices));
        return aggs;
    }

    private static ObjectNode termsAgg(String field, int size) {
        return JSON.objectNode()
                .set("terms", JSON.objectNode().put("field", field).put("size", size));
    }

    private static ObjectNode withTop(ObjectNode agg, String field) {
        var top = JSON.objectNode().put("size", 1);
        top.set("_source", array(field));
        agg.set("aggs", JSON.objectNode().set("top", JSON.objectNode().set("top_hits", top)));
        return agg;
    }

    private static ObjectNode term(String field, Object value) {
        var term = JSON.objectNode();
        switch (value) {
            case Boolean b -> term.put(field, b);
            case Integer i -> term.put(field, i);
            default -> term.put(field, value.toString());
        }
        return JSON.objectNode().set("term", term);
    }

    private static ObjectNode terms(String field, List<String> values) {
        var array = JSON.arrayNode();
        values.forEach(array::add);
        return JSON.objectNode().set("terms", JSON.objectNode().set(field, array));
    }

    private static ObjectNode point(double lat, double lng) {
        return JSON.objectNode().put("lat", lat).put("lon", lng);
    }

    private static ArrayNode array(String... values) {
        var array = JSON.arrayNode();
        for (var value : values) {
            array.add(value);
        }
        return array;
    }

    // ── the response ────────────────────────────────────────────────────────────────────────────────────────────

    static Hit hit(ListingDocument doc, SearchQuery q, SearchMoment now) {
        var near = q.near();
        var location = doc.location();
        Double distance = near == null || location == null
                ? null
                : Math.round(near.kmTo(location.lat(), location.lon()) * 10) / 10.0;
        var soldOut = now.today().equals(doc.soldOutOn());
        var paused = doc.pausedUntil() != null && doc.pausedUntil().isAfter(now.instant());
        var open = !soldOut
                && !paused
                && doc.openHours().stream().anyMatch(r -> r.gte() <= now.minuteOfWeek() && now.minuteOfWeek() < r.lt());
        var cutoff = doc.deliveryCutoffMinute();
        var tonight = doc.fulfilment().contains("pooled")
                && cutoff != null
                && now.minuteOfDay() < cutoff
                && Boolean.TRUE.equals(doc.inStock());
        return new Hit(doc, distance, open, soldOut, tonight);
    }

    private Facets facets(Map<String, Aggregate> aggs) {
        if (aggs.isEmpty()) {
            return Facets.NONE;
        }
        return new Facets(
                buckets(aggs.get("kinds"), null),
                buckets(aggs.get("categories"), "categoryNames"),
                buckets(aggs.get("merchants"), "merchantName"),
                buckets(aggs.get("tiers"), null),
                prices(aggs.get("prices")),
                buckets(aggs.get("dietary"), null));
    }

    private static List<Bucket> buckets(@Nullable Aggregate agg, @Nullable String labelField) {
        if (agg == null || !agg.isSterms()) {
            return List.of();
        }
        return agg.sterms().buckets().array().stream()
                .map(b ->
                        new Bucket(b.key().stringValue(), label(b.aggregations().get("top"), labelField), b.docCount()))
                .toList();
    }

    private static @Nullable String label(@Nullable Aggregate top, @Nullable String field) {
        if (top == null
                || field == null
                || !top.isTopHits()
                || top.topHits().hits().hits().isEmpty()) {
            return null;
        }
        var source = top.topHits().hits().hits().getFirst().source();
        if (source == null) {
            return null;
        }
        var value = source.toJson().asJsonObject().get(field);
        if (value == null) {
            return null;
        }
        return switch (value.getValueType()) {
            case STRING -> ((jakarta.json.JsonString) value).getString();
            case ARRAY -> {
                var array = value.asJsonArray();
                yield array.isEmpty() ? null : array.getString(array.size() - 1);
            }
            default -> null;
        };
    }

    private static List<Bucket> prices(@Nullable Aggregate agg) {
        if (agg == null || !agg.isRange()) {
            return List.of();
        }
        return agg.range().buckets().array().stream()
                .map(b -> new Bucket(Objects.requireNonNullElse(b.key(), ""), null, b.docCount()))
                .toList();
    }

    private static List<CompletionSuggestOption<ListingDocument>> options(
            @Nullable List<co.elastic.clients.elasticsearch.core.search.Suggestion<ListingDocument>> suggestions) {
        if (suggestions == null) {
            return List.of();
        }
        return suggestions.stream()
                .filter(s -> s.isCompletion())
                .flatMap(s -> s.completion().options().stream())
                .toList();
    }

    /** The category name (as the document has it) that the matched completion input ends. */
    private static String categoryName(ListingDocument doc, String matched) {
        var lower = matched.toLowerCase(Locale.ROOT);
        return doc.categoryNames().stream()
                .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(lower))
                .reduce((first, second) -> second)
                .orElse(matched);
    }

    // ── cursors ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** {@code <sort>.<base64url JSON of the last hit's sort values>}. */
    String cursor(SearchSort sort, List<FieldValue> values) {
        var array = JSON.arrayNode();
        for (var value : values) {
            switch (value._kind()) {
                case Long -> array.add(value.longValue());
                case Double -> array.add(value.doubleValue());
                case Boolean -> array.add(value.booleanValue());
                case Null -> array.addNull();
                case String -> array.add(value.stringValue());
                default -> array.add(String.valueOf(value._get()));
            }
        }
        var encoded = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(json.writeValueAsString(array).getBytes(StandardCharsets.UTF_8));
        return sort.code() + "." + encoded;
    }

    JsonNode decode(SearchSort sort, String token) {
        var dot = token.indexOf('.');
        if (dot < 0 || !token.substring(0, dot).equals(sort.code())) {
            throw RuleViolation.of("after", "invalid", SearchMessages.AFTER);
        }
        try {
            var node = json.readTree(
                    new String(Base64.getUrlDecoder().decode(token.substring(dot + 1)), StandardCharsets.UTF_8));
            if (!node.isArray() || node.isEmpty() || node.size() > 5) {
                throw RuleViolation.of("after", "invalid", SearchMessages.AFTER);
            }
            for (var value : node) {
                if (!(value.isNumber() || value.isString() || value.isBoolean() || value.isNull())) {
                    throw RuleViolation.of("after", "invalid", SearchMessages.AFTER);
                }
            }
            return node;
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException e) {
            throw RuleViolation.of("after", "invalid", SearchMessages.AFTER);
        }
    }

    /** Named buckets in a stable order, for tests. */
    static Map<String, Long> counts(List<Bucket> buckets) {
        var out = new LinkedHashMap<String, Long>();
        buckets.forEach(b -> out.put(b.value(), b.count()));
        return out;
    }
}
