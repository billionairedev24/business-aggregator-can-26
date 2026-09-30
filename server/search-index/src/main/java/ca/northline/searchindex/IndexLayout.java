package ca.northline.searchindex;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The listings indices as versioned code (S-42): {@code deploy/search/listings.json} (schema version, settings,
 * mappings), {@code analysis-<lang>.json} (analyzers per language) and {@code synonyms-<lang>.txt}, packaged as
 * {@code classpath:search/}. One {@link Definition} per {@link SearchLanguage}: the same fields in both languages, each
 * with its own analyzers under the same names ({@code nl_text}, {@code nl_text_search}, {@code nl_prefix},
 * {@code nl_keyword}).
 *
 * <p>Concrete indices are named {@code listings_<lang>_v<schema>_<yyyyMMddHHmmss>} and served through the alias
 * {@code listings_<lang>}, so a reindex (S-71) builds the next one beside the live one and swaps the alias. Every index
 * carries {@code _meta.northline} = schema version + a hash of its analysis and of its mappings: the bootstrap compares
 * them with the files to tell "in sync", "new fields to add in place" and "reindex required" apart.
 */
public final class IndexLayout {

    public static final String FAMILY = "listings";

    private static final Pattern INDEX_NAME = Pattern.compile(FAMILY + "_(en|fr)_v(\\d+)_(\\d{14})");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final int schema;
    private final Map<SearchLanguage, Definition> definitions;
    private final Map<SearchLanguage, List<String>> synonyms;

    private IndexLayout(
            int schema, Map<SearchLanguage, Definition> definitions, Map<SearchLanguage, List<String>> synonyms) {
        this.schema = schema;
        this.definitions = Map.copyOf(definitions);
        this.synonyms = Map.copyOf(synonyms);
    }

    /** What an index of one language is created with, and the hashes recorded in its {@code _meta}. */
    public record Definition(
            SearchLanguage language,
            int schema,
            ObjectNode settings,
            ObjectNode mappings,
            String analysisHash,
            String mappingsHash) {

        /** {@code _meta.northline} of an index built from this definition. */
        public IndexMeta meta() {
            return new IndexMeta(schema, analysisHash, mappingsHash);
        }

        /** The mappings with {@code _meta} (create index, put mapping). */
        public ObjectNode mappingsWithMeta() {
            var copy = mappings.deepCopy();
            copy.putObject("_meta").set("northline", meta().toJson());
            return copy;
        }

        /**
         * The create-index body: settings (with {@code overrides}, e.g. no refresh and no replicas while a reindex
         * loads it), mappings with {@code _meta}, and the alias when the index should serve at once.
         */
        public String createBody(Map<String, String> overrides, boolean withAlias) {
            var body = JsonNodeFactory.instance.objectNode();
            var settings = body.putObject("settings");
            settings.setAll(this.settings.deepCopy());
            overrides.forEach(settings::put);
            body.set("mappings", mappingsWithMeta());
            if (withAlias) {
                body.putObject("aliases").putObject(language.alias()).put("is_write_index", true);
            }
            return JSON.writeValueAsString(body);
        }
    }

    /** {@code _meta.northline} of a live index. */
    public record IndexMeta(int schema, String analysisHash, String mappingsHash) {

        ObjectNode toJson() {
            var node = JsonNodeFactory.instance.objectNode();
            node.put("schema", schema);
            node.put("analysisHash", analysisHash);
            node.put("mappingsHash", mappingsHash);
            return node;
        }

        /** Reads {@code {"schema":…,"analysisHash":…,"mappingsHash":…}}; empty when any part is missing. */
        public static Optional<IndexMeta> parse(String json) {
            var node = JSON.readTree(json);
            if (!node.path("schema").isInt()
                    || !node.path("analysisHash").isString()
                    || !node.path("mappingsHash").isString()) {
                return Optional.empty();
            }
            return Optional.of(new IndexMeta(
                    node.get("schema").asInt(),
                    node.get("analysisHash").asString(),
                    node.get("mappingsHash").asString()));
        }
    }

    /** The layout packaged with the app ({@code classpath:search/}). */
    public static IndexLayout fromClasspath() {
        return load(name -> {
            try (InputStream in = IndexLayout.class.getClassLoader().getResourceAsStream("search/" + name)) {
                if (in == null) {
                    throw new IllegalStateException("classpath:search/" + name + " is missing");
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** A layout in a directory laid out like {@code deploy/search} (tests of a changed layout). */
    public static IndexLayout fromDirectory(Path directory) {
        return load(name -> {
            try {
                return Files.readString(directory.resolve(name));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    static IndexLayout load(Function<String, String> files) {
        var listings = JSON.readTree(files.apply("listings.json"));
        var schema = listings.path("schema").asInt(0);
        if (schema < 1) {
            throw new IllegalStateException("listings.json: schema must be a positive integer");
        }
        var definitions = new EnumMap<SearchLanguage, Definition>(SearchLanguage.class);
        var synonyms = new EnumMap<SearchLanguage, List<String>>(SearchLanguage.class);
        for (var language : SearchLanguage.values()) {
            var analysis = withoutComment(JSON.readTree(files.apply("analysis-" + language.code() + ".json")));
            var settings = withoutComment(listings.required("settings")).deepCopy();
            settings.putObject("analysis").setAll(analysis);
            var mappings = withoutComment(listings.required("mappings"));
            definitions.put(
                    language, new Definition(language, schema, settings, mappings, hash(settings), hash(mappings)));
            synonyms.put(language, parseSynonyms(files.apply("synonyms-" + language.code() + ".txt")));
        }
        return new IndexLayout(schema, definitions, synonyms);
    }

    public int schema() {
        return schema;
    }

    public Definition definition(SearchLanguage language) {
        return Objects.requireNonNull(definitions.get(language));
    }

    /** The synonym rules of a language, in Solr format ({@code a, b, c} or {@code a => b}), file order. */
    public List<String> synonyms(SearchLanguage language) {
        return Objects.requireNonNull(synonyms.get(language));
    }

    /** {@code listings_<lang>_v<schema>_<yyyyMMddHHmmss>} (UTC) — a new concrete index of this layout. */
    public String newIndexName(SearchLanguage language, Instant at) {
        return "%s_v%d_%s".formatted(language.alias(), schema, STAMP.format(at));
    }

    /** True for the names {@link #newIndexName} produces for {@code language} (any schema). */
    public static boolean isIndexOf(SearchLanguage language, String index) {
        var m = INDEX_NAME.matcher(index);
        return m.matches() && m.group(1).equals(language.code());
    }

    /** Synonym rules of a file: one rule per line; blank lines and {@code #} comments are skipped. */
    static List<String> parseSynonyms(String text) {
        var rules = new ArrayList<String>();
        var lineNo = 0;
        for (var line : text.split("\n", -1)) {
            lineNo++;
            var rule = line.strip();
            if (rule.isEmpty() || rule.startsWith("#")) {
                continue;
            }
            if (!rule.contains(",") && !rule.contains("=>")) {
                throw new IllegalStateException(
                        "synonyms line " + lineNo + " is neither \"a, b\" nor \"a => b\": " + rule);
            }
            rules.add(rule);
        }
        return List.copyOf(rules);
    }

    private static ObjectNode withoutComment(JsonNode node) {
        if (!(node instanceof ObjectNode object)) {
            throw new IllegalStateException("expected a JSON object, got " + node);
        }
        var copy = object.deepCopy();
        copy.remove("$comment");
        return copy;
    }

    /** SHA-256 of the node with object keys sorted at every level (key order in the files never matters). */
    static String hash(JsonNode node) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsString(canonical(node)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object canonical(JsonNode node) {
        if (node.isObject()) {
            var sorted = new TreeMap<String, Object>();
            node.properties().forEach(e -> sorted.put(e.getKey(), canonical(e.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            return node.valueStream().map(IndexLayout::canonical).toList();
        }
        return node;
    }
}
