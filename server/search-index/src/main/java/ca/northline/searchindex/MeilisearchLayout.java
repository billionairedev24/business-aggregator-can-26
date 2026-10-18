package ca.northline.searchindex;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The listings indices on Meilisearch as versioned code: {@code deploy/search/meilisearch.json} (schema, primary key,
 * locales, index settings), {@code stopwords-<lang>.txt} and the same {@code synonyms-<lang>.txt} as Elasticsearch,
 * packaged as {@code classpath:search/}. One settings object per {@link SearchLanguage}.
 *
 * <p>Synonyms: Meilisearch takes a map {@code word → [synonyms]} per index (no synonym sets): a Solr rule
 * {@code a, b, c} becomes {@code a → [b, c]}, {@code b → [a, c]}, {@code c → [a, b]}; {@code a, b => c} becomes
 * {@code a → [c]}, {@code b → [c]} (one way). A term in several rules gets all of their synonyms.
 */
public final class MeilisearchLayout {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final int schema;
    private final String primaryKey;
    private final Map<SearchLanguage, ObjectNode> settings;

    private MeilisearchLayout(int schema, String primaryKey, Map<SearchLanguage, ObjectNode> settings) {
        this.schema = schema;
        this.primaryKey = primaryKey;
        this.settings = Map.copyOf(settings);
    }

    /** The layout packaged with the app ({@code classpath:search/}). */
    public static MeilisearchLayout fromClasspath() {
        return load(name -> {
            try (InputStream in = MeilisearchLayout.class.getClassLoader().getResourceAsStream("search/" + name)) {
                if (in == null) {
                    throw new IllegalStateException("classpath:search/" + name + " is missing");
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    static MeilisearchLayout load(Function<String, String> files) {
        var root = JSON.readTree(files.apply("meilisearch.json"));
        var schema = root.path("schema").asInt(0);
        if (schema < 1) {
            throw new IllegalStateException("meilisearch.json: schema must be a positive integer");
        }
        if (!(root.get("settings") instanceof ObjectNode base)) {
            throw new IllegalStateException("meilisearch.json: settings must be an object");
        }
        var settings = new EnumMap<SearchLanguage, ObjectNode>(SearchLanguage.class);
        for (var language : SearchLanguage.values()) {
            var rendered = base.deepCopy();
            var stopWords = rendered.putArray("stopWords");
            words(files.apply("stopwords-" + language.code() + ".txt")).forEach(stopWords::add);
            rendered.set(
                    "synonyms",
                    synonyms(IndexLayout.parseSynonyms(files.apply("synonyms-" + language.code() + ".txt"))));
            var locales = root.path("locales").path(language.code());
            if (!locales.isArray() || locales.isEmpty()) {
                throw new IllegalStateException("meilisearch.json: locales." + language.code() + " is missing");
            }
            var localized = rendered.putArray("localizedAttributes").addObject();
            localized.putArray("attributePatterns").add("*");
            localized.set("locales", locales.deepCopy());
            settings.put(language, rendered);
        }
        return new MeilisearchLayout(schema, root.path("primaryKey").asString("id"), settings);
    }

    public int schema() {
        return schema;
    }

    public String primaryKey() {
        return primaryKey;
    }

    /** The full settings of the language's index (a copy). */
    public ObjectNode settings(SearchLanguage language) {
        return Objects.requireNonNull(settings.get(language)).deepCopy();
    }

    /** {@code listings_<lang>_v<schema>_<yyyyMMddHHmmss>} — a new versioned index of this layout (reindex). */
    public String newIndexName(SearchLanguage language, java.time.Instant at) {
        return IndexLayout.indexName(language, schema, at);
    }

    /** Solr rules ({@link IndexLayout#synonyms}) as Meilisearch's synonym map, keys and values sorted. */
    static ObjectNode synonyms(List<String> rules) {
        var map = new TreeMap<String, TreeSet<String>>();
        for (var rule : rules) {
            var arrow = rule.indexOf("=>");
            if (arrow >= 0) {
                var targets = terms(rule.substring(arrow + 2));
                for (var source : terms(rule.substring(0, arrow))) {
                    targets.stream()
                            .filter(t -> !t.equals(source))
                            .forEach(t -> map.computeIfAbsent(source, _ -> new TreeSet<>())
                                    .add(t));
                }
            } else {
                var terms = terms(rule);
                for (var term : terms) {
                    terms.stream()
                            .filter(t -> !t.equals(term))
                            .forEach(t -> map.computeIfAbsent(term, _ -> new TreeSet<>())
                                    .add(t));
                }
            }
        }
        var node = JsonNodeFactory.instance.objectNode();
        map.forEach((term, synonyms) -> {
            var array = node.putArray(term);
            synonyms.forEach(array::add);
        });
        return node;
    }

    private static List<String> terms(String part) {
        var out = new ArrayList<String>();
        for (var term : part.split(",")) {
            var t = term.strip().toLowerCase(Locale.ROOT);
            if (!t.isEmpty() && !out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /** One word per line; blank lines and {@code #} comments skipped; file order, no duplicates. */
    static List<String> words(String text) {
        var out = new ArrayList<String>();
        for (var line : text.split("\n", -1)) {
            var word = line.strip().toLowerCase(Locale.ROOT);
            if (!word.isEmpty() && !word.startsWith("#") && !out.contains(word)) {
                out.add(word);
            }
        }
        return List.copyOf(out);
    }
}
