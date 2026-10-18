package ca.northline.searchindex;

import java.util.Map;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A {@link ListingDocument} as a Meilisearch document, and back. The document keeps every field of the Elasticsearch
 * one (the api reads it back unchanged) plus what Meilisearch needs to filter, sort and count the same things,
 * because it has no range fields, no top hits per facet and no script scoring:
 *
 * <ul>
 *   <li>{@code _geo} {@code {lat, lng}} — Meilisearch's reserved geo field (radius filter, distance sort);
 *       {@code hasLocation} — "has a location" ({@code _geo} itself can't be filtered on);
 *   <li>{@code openSlots} — the quarter hours of the week ({@code minute / 15}, Monday 00:00 local time = 0) that
 *       {@code openHours} covers entirely: open now = {@code openSlots = <now / 15>};
 *   <li>{@code pausedUntilEpoch} — {@code pausedUntil} in epoch seconds (filters compare numbers);
 *   <li>{@code priceBucket} — the price facet's range ({@code under_10} … {@code 100_plus});
 *   <li>{@code categoryFacet} / {@code merchantFacet} — {@code <id>␟<name>}: the facet value carries its label;
 *   <li>{@code suggestWeight} (sortable) and {@code categorySuggest} (the document suggests its category).
 * </ul>
 */
public final class MeilisearchDocuments {

    /** Quarter hours: openSlots holds the slots a listing is open for the whole of. */
    public static final int SLOT_MINUTES = 15;

    public static final int WEEK_SLOTS = 7 * 24 * 60 / SLOT_MINUTES;

    /** Unit separator between a facet value's id and its label. */
    public static final String FACET_SEPARATOR = "\u001f";

    public static final String GEO = "_geo";
    public static final String HAS_LOCATION = "hasLocation";
    public static final String OPEN_SLOTS = "openSlots";
    public static final String PAUSED_UNTIL_EPOCH = "pausedUntilEpoch";
    public static final String PRICE_BUCKET = "priceBucket";
    public static final String CATEGORY_FACET = "categoryFacet";
    public static final String MERCHANT_FACET = "merchantFacet";
    public static final String SUGGEST_WEIGHT = "suggestWeight";
    public static final String CATEGORY_SUGGEST = "categorySuggest";

    /** The price facet's ranges in order, as the Elasticsearch range aggregation names them. */
    public static final java.util.List<String> PRICE_RANGES =
            java.util.List.of("under_10", "10_25", "25_50", "50_100", "100_plus");

    /** Their upper bounds in cents (exclusive); the last range has none. */
    private static final long[] PRICE_BOUNDS = {1_000, 2_500, 5_000, 10_000};

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final java.util.Set<String> DERIVED = java.util.Set.of(
            HAS_LOCATION,
            OPEN_SLOTS,
            PAUSED_UNTIL_EPOCH,
            PRICE_BUCKET,
            CATEGORY_FACET,
            MERCHANT_FACET,
            SUGGEST_WEIGHT,
            CATEGORY_SUGGEST);

    private MeilisearchDocuments() {}

    public static ObjectNode toMeilisearch(ListingDocument doc) {
        ObjectNode node = JSON.valueToTree(doc);
        var nulls = node.properties().stream()
                .filter(e -> e.getValue().isNull())
                .map(Map.Entry::getKey)
                .toList();
        nulls.forEach(node::remove);
        var location = doc.location();
        node.put(HAS_LOCATION, location != null);
        if (location != null) {
            node.putObject(GEO).put("lat", location.lat()).put("lng", location.lon());
        }
        var slots = node.putArray(OPEN_SLOTS);
        openSlots(doc).forEach(slots::add);
        if (doc.pausedUntil() != null) {
            node.put(PAUSED_UNTIL_EPOCH, doc.pausedUntil().getEpochSecond());
        }
        if (doc.priceCents() != null) {
            node.put(PRICE_BUCKET, priceBucket(doc.priceCents()));
        }
        if (doc.categoryId() != null) {
            var names = doc.categoryNames();
            node.put(CATEGORY_FACET, doc.categoryId() + FACET_SEPARATOR + (names.isEmpty() ? "" : names.getLast()));
        }
        node.put(MERCHANT_FACET, doc.merchantId() + FACET_SEPARATOR + doc.merchantName());
        node.put(SUGGEST_WEIGHT, doc.suggest().weight());
        node.put(CATEGORY_SUGGEST, doc.suggestCategory() != null);
        return node;
    }

    /** A hit or a fetched document back as the document the worker built (derived and {@code _…} fields dropped). */
    public static ListingDocument fromMeilisearch(JsonNode hit) {
        var copy = (ObjectNode) hit.deepCopy();
        var drop = copy.propertyNames().stream()
                .filter(name -> name.startsWith("_") || DERIVED.contains(name))
                .toList();
        drop.forEach(copy::remove);
        return JSON.treeToValue(copy, ListingDocument.class);
    }

    /** The quarter hours of the week the listing's weekly hours cover entirely. */
    public static java.util.SortedSet<Integer> openSlots(ListingDocument doc) {
        var slots = new TreeSet<Integer>();
        for (var range : doc.openHours()) {
            var first = Math.max(0, Math.ceilDiv(range.gte(), SLOT_MINUTES));
            var last = Math.min(WEEK_SLOTS, Math.floorDiv(range.lt(), SLOT_MINUTES));
            for (var slot = first; slot < last; slot++) {
                slots.add(slot);
            }
        }
        return slots;
    }

    /** The quarter hour of the week a minute of the week falls in. */
    public static int slot(int minuteOfWeek) {
        return minuteOfWeek / SLOT_MINUTES;
    }

    public static String priceBucket(long priceCents) {
        var i = 0;
        while (i < PRICE_BOUNDS.length && priceCents >= PRICE_BOUNDS[i]) {
            i++;
        }
        return PRICE_RANGES.get(i);
    }

    /** A string as a quoted Meilisearch filter value ({@code "…"}, with {@code \} and {@code "} escaped). */
    public static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
