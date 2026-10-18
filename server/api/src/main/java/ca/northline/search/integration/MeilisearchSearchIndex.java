package ca.northline.search.integration;

import static ca.northline.searchindex.MeilisearchDocuments.quote;

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
import ca.northline.searchindex.MeilisearchClient;
import ca.northline.searchindex.MeilisearchDocuments;
import ca.northline.shared.CodedEnum;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link SearchIndex} on Meilisearch (provider {@code meilisearch}): the indexes {@code listings_en} /
 * {@code listings_fr} (the query's language picks one), filled by the worker with {@link MeilisearchDocuments}. Same
 * rules, filters, facets and response as {@link ElasticsearchSearchIndex}; where Meilisearch can't do what
 * Elasticsearch does, the approximation is described here and in docs/DECISIONS.md (Meilisearch search provider).
 *
 * <ul>
 *   <li><b>Match:</b> name, merchant name, category names, keywords, description (that order is the attribute
 *       ranking), the index's synonyms, stop words and locale; every word must match ({@code matchingStrategy: all}),
 *       the last one as a prefix. No stemming (typo tolerance covers plurals of words of 5 letters and more).
 *   <li><b>Filters:</b> the market, approved + live + active, then the query's; open now through the quarter-hour
 *       slots the worker indexes ({@code openSlots}), pauses as epoch seconds.
 *   <li><b>Order</b> (the index's ranking rules: {@code sort} first, then the text rules, then trust tier and
 *       rating): an explicit sort is strict, as on Elasticsearch; relevance = text match, then trust tier, then
 *       rating — with a location, nearest first (Meilisearch can't add nearness to a score). Ties by id for explicit
 *       sorts.
 *   <li><b>Pages:</b> offset based (Meilisearch has no search_after); the token names its sort and carries the next
 *       offset. Exact totals up to 10 000 (the index's {@code maxTotalHits}), like {@code track_total_hits}.
 *   <li><b>Facets</b> on the first page only, from {@code facetDistribution}.
 * </ul>
 */
public final class MeilisearchSearchIndex implements SearchIndex {

    static final int MAX_TOTAL = ElasticsearchSearchIndex.MAX_TOTAL;

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final List<String> FACETS = List.of(
            "kind",
            MeilisearchDocuments.CATEGORY_FACET,
            MeilisearchDocuments.MERCHANT_FACET,
            "trustTier",
            MeilisearchDocuments.PRICE_BUCKET,
            "dietary");

    private final MeilisearchClient meili;
    private final JsonMapper json;

    public MeilisearchSearchIndex(MeilisearchClient meili, JsonMapper json) {
        this.meili = meili;
        this.json = json;
    }

    @Override
    public SearchResults search(SearchQuery query, SearchMoment now) {
        var offset = query.after() == null ? 0 : decode(query.sort(), query.after());
        var response = meili.search(query.language().alias(), body(query, now, offset));
        var hits = new ArrayList<Hit>();
        for (var hit : response.path("hits")) {
            hits.add(SearchHits.hit(MeilisearchDocuments.fromMeilisearch(hit), query, now));
        }
        var total = response.has("totalHits")
                ? response.path("totalHits").asLong()
                : response.path("estimatedTotalHits").asLong(hits.size());
        var reach = offset + hits.size();
        var next =
                hits.size() == query.size() && reach < Math.min(total, MAX_TOTAL) ? cursor(query.sort(), reach) : null;
        var facets = query.after() == null ? facets(response.path("facetDistribution")) : Facets.NONE;
        return new SearchResults(List.copyOf(hits), total, facets, next);
    }

    @Override
    public List<Suggestion> suggest(SuggestQuery query) {
        var market = "market = " + quote(query.market());
        var kinds = query.kinds().stream().map(CodedEnum::code).sorted().toList();
        var listings = JSON.objectNode()
                .put("indexUid", query.language().alias())
                .put("q", query.prefix())
                .put("matchingStrategy", "all")
                .put("limit", query.size() * 2) // room for the duplicates skipped below
                .put("filter", kinds.isEmpty() ? market : market + " AND kind IN " + list(kinds));
        listings.putArray("attributesToSearchOn").add("name");
        listings.putArray("sort").add(MeilisearchDocuments.SUGGEST_WEIGHT + ":desc");
        var categoryQuery = JSON.objectNode()
                .put("indexUid", query.language().alias())
                .put("q", query.prefix())
                .put("matchingStrategy", "all")
                .put("limit", 10)
                .put("distinct", "categoryId")
                .put("filter", market + " AND " + MeilisearchDocuments.CATEGORY_SUGGEST + " = true");
        categoryQuery.putArray("attributesToSearchOn").add("categoryNames");
        categoryQuery.putArray("sort").add(MeilisearchDocuments.SUGGEST_WEIGHT + ":desc");
        var results = meili.multiSearch(List.of(listings, categoryQuery));

        var out = new ArrayList<Suggestion>();
        var texts = new HashSet<String>();
        for (var hit : results.get(0).path("hits")) {
            var doc = MeilisearchDocuments.fromMeilisearch(hit);
            if (texts.add(doc.name().toLowerCase(Locale.ROOT))) {
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
        }
        var categories = new ArrayList<Suggestion>();
        var seen = new HashSet<String>();
        for (var hit : results.get(1).path("hits")) {
            var doc = MeilisearchDocuments.fromMeilisearch(hit);
            var name = categoryName(doc, query.prefix());
            if (doc.categoryId() != null
                    && name != null
                    && categories.size() < Math.min(3, query.size())
                    && seen.add(name.toLowerCase(Locale.ROOT))) {
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
        // listings first; categories keep up to two of the places (as on Elasticsearch)
        var forCategories = Math.min(Math.min(2, categories.size()), query.size());
        var listingsShown = Math.min(out.size(), query.size() - forCategories);
        var result = new ArrayList<>(out.subList(0, listingsShown));
        result.addAll(categories.subList(0, Math.min(categories.size(), query.size() - listingsShown)));
        return List.copyOf(result);
    }

    // ── the request ─────────────────────────────────────────────────────────────────────────────────────────────

    ObjectNode body(SearchQuery q, SearchMoment now, int offset) {
        var body = JSON.objectNode();
        body.put("q", Objects.requireNonNullElse(q.text(), ""));
        body.put("matchingStrategy", "all");
        body.put("filter", String.join(" AND ", filters(q, now)));
        var sort = sort(q);
        if (!sort.isEmpty()) {
            var array = body.putArray("sort");
            sort.forEach(array::add);
        }
        if (offset % q.size() == 0) { // pages of the same size: exact totalHits
            body.put("hitsPerPage", q.size()).put("page", offset / q.size() + 1);
        } else {
            body.put("offset", offset).put("limit", q.size());
        }
        if (q.after() == null) {
            var facets = body.putArray("facets");
            FACETS.forEach(facets::add);
        }
        return body;
    }

    static List<String> filters(SearchQuery q, SearchMoment now) {
        var filters = new ArrayList<String>();
        filters.add("market = " + quote(q.market()));
        filters.add("vetting = \"approved\"");
        filters.add("status = \"live\"");
        filters.add("merchantStatus = \"active\"");
        if (!q.kinds().isEmpty()) {
            filters.add("kind IN "
                    + list(q.kinds().stream().map(CodedEnum::code).sorted().toList()));
        }
        if (q.categoryId() != null) {
            filters.add("categoryPath = " + quote(q.categoryId()));
        }
        if (q.minPriceCents() != null) {
            filters.add("priceCents >= " + q.minPriceCents());
        }
        if (q.maxPriceCents() != null) {
            filters.add("priceCents <= " + q.maxPriceCents());
        }
        if (q.minRating() != null) {
            filters.add("rating >= " + number(q.minRating()));
        }
        if (!q.tiers().isEmpty()) {
            filters.add("trustTier IN "
                    + list(q.tiers().stream().map(CodedEnum::code).sorted().toList()));
        }
        if (q.instantBook()) {
            filters.add("instantBook = true");
        }
        if (q.openNow()) {
            filters.add(MeilisearchDocuments.OPEN_SLOTS + " = " + MeilisearchDocuments.slot(now.minuteOfWeek()));
            filters.add("NOT " + MeilisearchDocuments.PAUSED_UNTIL_EPOCH + " > "
                    + now.instant().getEpochSecond());
            filters.add("NOT soldOutOn = " + quote(now.today().toString()));
        }
        if (q.deliveryTonight()) {
            filters.add("fulfilment = \"pooled\"");
            filters.add("inStock = true");
            filters.add("deliveryCutoffMinute > " + now.minuteOfDay());
        }
        q.dietary().stream().sorted().forEach(tag -> filters.add("dietary = " + quote(tag)));
        if (!q.allergenFree().isEmpty()) {
            filters.add("NOT allergens IN "
                    + list(q.allergenFree().stream().sorted().toList()));
        }
        var near = q.near();
        if (near != null && q.radiusKm() != null) {
            filters.add("_geoRadius(%s, %s, %d)"
                    .formatted(number(near.lat()), number(near.lng()), Math.round(q.radiusKm() * 1000)));
        }
        if (q.sort() == SearchSort.DISTANCE) {
            filters.add(MeilisearchDocuments.HAS_LOCATION + " = true");
        }
        return filters;
    }

    static List<String> sort(SearchQuery q) {
        var near = q.near();
        var nearest = near == null ? null : "_geoPoint(%s, %s):asc".formatted(number(near.lat()), number(near.lng()));
        return switch (q.sort()) {
            case RELEVANCE -> nearest == null ? List.of() : List.of(nearest);
            case DISTANCE -> List.of(Objects.requireNonNull(nearest), "id:asc");
            case PRICE_ASC -> List.of("priceCents:asc", "id:asc");
            case PRICE_DESC -> List.of("priceCents:desc", "id:asc");
            case RATING -> List.of("rating:desc", "reviewCount:desc", "trustRank:desc", "id:asc");
        };
    }

    /** Plain decimal notation (Meilisearch's filter syntax has no exponents). */
    private static String number(double value) {
        return java.math.BigDecimal.valueOf(value).toPlainString();
    }

    private static String list(List<String> values) {
        return values.stream().map(MeilisearchDocuments::quote).collect(Collectors.joining(", ", "[", "]"));
    }

    // ── the response ────────────────────────────────────────────────────────────────────────────────────────────

    private static Facets facets(JsonNode distribution) {
        if (!distribution.isObject()) {
            return Facets.NONE;
        }
        var prices = new ArrayList<Bucket>();
        var priced = distribution.path(MeilisearchDocuments.PRICE_BUCKET);
        for (var range : MeilisearchDocuments.PRICE_RANGES) {
            prices.add(new Bucket(range, null, priced.path(range).asLong(0)));
        }
        return new Facets(
                buckets(distribution.path("kind"), 4, false),
                buckets(distribution.path(MeilisearchDocuments.CATEGORY_FACET), 20, true),
                buckets(distribution.path(MeilisearchDocuments.MERCHANT_FACET), 20, true),
                buckets(distribution.path("trustTier"), 3, false),
                List.copyOf(prices),
                buckets(distribution.path("dietary"), 20, false));
    }

    /**
     * Counts per value, most frequent first (ties by value), like a terms aggregation. Labelled facets carry
     * {@code <id>␟<label>}; one id with two labels (a rename the indexer hasn't reached everywhere) counts once.
     */
    private static List<Bucket> buckets(JsonNode counts, int size, boolean labelled) {
        var merged = new LinkedHashMap<String, Bucket>();
        counts.properties().forEach(e -> {
            var value = e.getKey();
            String label = null;
            if (labelled) {
                var at = value.indexOf(MeilisearchDocuments.FACET_SEPARATOR);
                label = at < 0 ? null : value.substring(at + 1);
                value = at < 0 ? value : value.substring(0, at);
                label = label == null || label.isEmpty() ? null : label;
            }
            var count = e.getValue().asLong();
            var finalLabel = label;
            merged.merge(
                    value,
                    new Bucket(value, label, count),
                    (a, b) -> new Bucket(a.value(), a.label() != null ? a.label() : finalLabel, a.count() + b.count()));
        });
        return merged.values().stream()
                .sorted(Comparator.comparingLong(Bucket::count).reversed().thenComparing(Bucket::value))
                .limit(size)
                .toList();
    }

    /** The deepest category name of the document a word of which starts with what was typed. */
    private static @Nullable String categoryName(ListingDocument doc, String typed) {
        var names = doc.categoryNames();
        for (var i = names.size() - 1; i >= 0; i--) {
            if (!Highlight.of(names.get(i), typed).isEmpty()) {
                return names.get(i);
            }
        }
        return null;
    }

    // ── cursors ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** {@code <sort>.<base64url JSON [offset]>} — the same shape as the Elasticsearch token, another content. */
    String cursor(SearchSort sort, int offset) {
        var array = JSON.arrayNode().add(offset);
        return sort.code() + "."
                + Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(json.writeValueAsString(array).getBytes(StandardCharsets.UTF_8));
    }

    int decode(SearchSort sort, String token) {
        var dot = token.indexOf('.');
        if (dot < 0 || !token.substring(0, dot).equals(sort.code())) {
            throw invalidToken();
        }
        try {
            var node = json.readTree(
                    new String(Base64.getUrlDecoder().decode(token.substring(dot + 1)), StandardCharsets.UTF_8));
            if (!node.isArray() || node.size() != 1 || !node.get(0).canConvertToInt()) {
                throw invalidToken();
            }
            var offset = node.get(0).asInt();
            if (offset < 1 || offset >= MAX_TOTAL) {
                throw invalidToken();
            }
            return offset;
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException e) {
            throw invalidToken();
        }
    }

    private static ca.northline.shared.RuleViolation invalidToken() {
        return ca.northline.shared.RuleViolation.of("after", "invalid", SearchMessages.AFTER);
    }
}
