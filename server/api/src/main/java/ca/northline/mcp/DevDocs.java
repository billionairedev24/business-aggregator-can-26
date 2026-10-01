package ca.northline.mcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The developer documentation the docs MCP server reads (S-128): the repository's {@code docs/} Markdown — runbooks,
 * architecture, conventions, decisions — and the committed OpenAPI documents ({@code docs/api/openapi/*.yaml}, S-125).
 * The build copies them into the api's classpath under {@value #ROOT} ({@code server/api/build.gradle.kts},
 * task {@code devDocs}), so the image carries the docs of the code it runs. Loaded once, read-only, in memory
 * (≈ 2 MB).
 */
public final class DevDocs {

    static final String ROOT = "northline-devdocs";
    private static final String OPENAPI_DIR = "api/openapi/";
    private static final Pattern HEADING = Pattern.compile("^(#{1,4})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_.\\-/]{2,}");
    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options");
    private static final int MAX_DEPTH = 6;

    /** One Markdown document. {@code path} is relative to {@code docs/}, e.g. {@code runbooks/mcp.md}. */
    public record Doc(String path, String title, String text, List<Section> sections) {}

    /** A heading and the text up to the next heading of any level. */
    public record Section(String heading, String anchor, int level, String text) {}

    /** One OpenAPI document; {@code name} is the file name without {@code .yaml}, e.g. {@code api-studio}. */
    public record Spec(String name, String path, String yaml, JsonNode root) {}

    public record Hit(String path, String title, String heading, String anchor, int score, String snippet) {}

    public record OperationRef(
            String spec, String method, String path, String operationId, String summary, List<String> tags) {}

    private final Map<String, Doc> docs;
    private final Map<String, Spec> specs;
    private final JsonMapper json;

    private DevDocs(Map<String, Doc> docs, Map<String, Spec> specs, JsonMapper json) {
        this.docs = docs;
        this.specs = specs;
        this.json = json;
    }

    /** Everything under {@code classpath*:northline-devdocs/} (empty when the build didn't package the docs). */
    public static DevDocs fromClasspath(JsonMapper json) {
        var docs = new TreeMap<String, Doc>();
        var specs = new TreeMap<String, Spec>();
        try {
            for (var resource : new PathMatchingResourcePatternResolver().getResources("classpath*:" + ROOT + "/**/*")) {
                var path = relativePath(resource);
                if (path == null || !resource.isReadable()) {
                    continue;
                }
                var text = resource.getContentAsString(StandardCharsets.UTF_8);
                if (path.endsWith(".md")) {
                    docs.put(path, parse(path, text));
                } else if (path.startsWith(OPENAPI_DIR) && path.endsWith(".yaml")) {
                    var name = path.substring(OPENAPI_DIR.length(), path.length() - ".yaml".length());
                    specs.put(name, new Spec(name, path, text, json.valueToTree(yaml().load(text))));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new DevDocs(docs, specs, json);
    }

    /** For tests: documents and specs given as {@code path → text}. */
    static DevDocs of(Map<String, String> files, JsonMapper json) {
        var docs = new TreeMap<String, Doc>();
        var specs = new TreeMap<String, Spec>();
        files.forEach((path, text) -> {
            if (path.endsWith(".md")) {
                docs.put(path, parse(path, text));
            } else {
                var name = path.substring(path.lastIndexOf('/') + 1, path.length() - ".yaml".length());
                specs.put(name, new Spec(name, path, text, json.valueToTree(yaml().load(text))));
            }
        });
        return new DevDocs(docs, specs, json);
    }

    public List<Doc> documents() {
        return List.copyOf(docs.values());
    }

    public Optional<Doc> document(String path) {
        return Optional.ofNullable(docs.get(normalise(path)));
    }

    public List<Spec> specs() {
        return List.copyOf(specs.values());
    }

    public Optional<Spec> spec(String name) {
        return Optional.ofNullable(specs.get(name.replaceFirst("\\.yaml$", "")));
    }

    /**
     * Sections ranked by how often the query's words occur (a match in the heading counts three times, in the
     * document's title twice); every word must occur in the section or its headings.
     */
    public List<Hit> search(String query, int limit) {
        var terms = WORD.matcher(query.toLowerCase(Locale.ROOT))
                .results()
                .map(m -> m.group())
                .distinct()
                .toList();
        if (terms.isEmpty()) {
            return List.of();
        }
        var hits = new ArrayList<Hit>();
        for (var doc : docs.values()) {
            var title = doc.title().toLowerCase(Locale.ROOT);
            for (var section : doc.sections()) {
                var heading = section.heading().toLowerCase(Locale.ROOT);
                var body = section.text().toLowerCase(Locale.ROOT);
                var score = 0;
                var all = true;
                for (var term : terms) {
                    var inBody = count(body, term);
                    var inHeading = count(heading, term);
                    var inTitle = count(title, term);
                    if (inBody + inHeading + inTitle == 0) {
                        all = false;
                        break;
                    }
                    score += inBody + 3 * inHeading + 2 * inTitle;
                }
                if (all) {
                    hits.add(new Hit(
                            doc.path(),
                            doc.title(),
                            section.heading(),
                            section.anchor(),
                            score,
                            snippet(section.text(), terms.getFirst())));
                }
            }
        }
        return hits.stream()
                .sorted(Comparator.comparingInt(Hit::score).reversed().thenComparing(Hit::path))
                .limit(Math.max(1, limit))
                .toList();
    }

    /** Operations of one spec (or of all), optionally only those whose path, id, summary or tags contain {@code query}. */
    public List<OperationRef> operations(@Nullable String specName, @Nullable String query) {
        var needle = query == null ? "" : query.toLowerCase(Locale.ROOT).strip();
        var result = new ArrayList<OperationRef>();
        for (var spec : specs.values()) {
            if (specName != null && !specName.isBlank() && !spec.name().equals(specName)) {
                continue;
            }
            for (var path : spec.root().path("paths").properties()) {
                for (var operation : path.getValue().properties()) {
                    if (!METHODS.contains(operation.getKey())) {
                        continue;
                    }
                    var op = operation.getValue();
                    var tags = new ArrayList<String>();
                    op.path("tags").forEach(t -> tags.add(t.asString("")));
                    var ref = new OperationRef(
                            spec.name(),
                            operation.getKey().toUpperCase(Locale.ROOT),
                            path.getKey(),
                            op.path("operationId").asString(""),
                            op.path("summary").asString(""),
                            List.copyOf(tags));
                    if (needle.isEmpty()
                            || (ref.path() + " " + ref.operationId() + " " + ref.summary() + " " + tags)
                                    .toLowerCase(Locale.ROOT)
                                    .contains(needle)) {
                        result.add(ref);
                    }
                }
            }
        }
        return result;
    }

    /**
     * One operation with its parameters, request body and responses, every {@code $ref} into the document's
     * components inlined (to a depth of {@value #MAX_DEPTH}; a recursive schema keeps its {@code $ref}).
     */
    public Optional<Map<String, Object>> operation(
            @Nullable String specName, @Nullable String operationId, @Nullable String method, @Nullable String path) {
        for (var ref : operations(specName, null)) {
            var byId = operationId != null && !operationId.isBlank() && operationId.equals(ref.operationId());
            var byPath = method != null
                    && path != null
                    && ref.method().equalsIgnoreCase(method)
                    && ref.path().equals(path);
            if (!byId && !byPath) {
                continue;
            }
            var spec = specs.get(ref.spec());
            if (spec == null) {
                continue;
            }
            var op = spec.root()
                    .path("paths")
                    .path(ref.path())
                    .path(ref.method().toLowerCase(Locale.ROOT));
            var shared = spec.root().path("paths").path(ref.path()).path("parameters");
            var result = new LinkedHashMap<String, Object>();
            result.put("spec", ref.spec());
            result.put("method", ref.method());
            result.put("path", ref.path());
            result.put("operationId", ref.operationId());
            putIfPresent(result, "summary", op.get("summary"));
            putIfPresent(result, "description", op.get("description"));
            var parameters = json.createArrayNode();
            shared.forEach(p -> parameters.add(resolve(spec.root(), p, new ArrayDeque<>())));
            op.path("parameters").forEach(p -> parameters.add(resolve(spec.root(), p, new ArrayDeque<>())));
            result.put("parameters", parameters);
            if (op.has("requestBody")) {
                result.put("requestBody", resolve(spec.root(), op.get("requestBody"), new ArrayDeque<>()));
            }
            result.put("responses", resolve(spec.root(), op.path("responses"), new ArrayDeque<>()));
            putIfPresent(result, "security", op.get("security"));
            return Optional.of(result);
        }
        return Optional.empty();
    }

    private JsonNode resolve(JsonNode root, JsonNode node, Deque<String> seen) {
        if (node.isObject()) {
            var ref = node.get("$ref");
            if (ref != null && ref.isString() && ref.asString().startsWith("#/")) {
                var pointer = ref.asString();
                if (seen.contains(pointer) || seen.size() >= MAX_DEPTH) {
                    return node;
                }
                var target = root.at(pointer.substring(1));
                if (target.isMissingNode()) {
                    return node;
                }
                seen.push(pointer);
                var resolved = resolve(root, target, seen);
                seen.pop();
                return resolved;
            }
            var copy = json.createObjectNode();
            for (var field : node.properties()) {
                copy.set(field.getKey(), resolve(root, field.getValue(), seen));
            }
            return copy;
        }
        if (node.isArray()) {
            var copy = json.createArrayNode();
            node.forEach(item -> copy.add(resolve(root, item, seen)));
            return copy;
        }
        return node;
    }

    private static void putIfPresent(Map<String, Object> map, String key, @Nullable JsonNode value) {
        if (value != null && !value.isNull() && !value.isMissingNode()) {
            map.put(key, value.isString() ? value.asString() : value);
        }
    }

    static Doc parse(String path, String text) {
        var sections = new ArrayList<Section>();
        var heading = path;
        var level = 0;
        var body = new StringBuilder();
        String title = null;
        var fenced = false;
        for (var line : text.split("\n", -1)) {
            if (line.startsWith("```")) {
                fenced = !fenced;
            }
            var m = fenced ? null : HEADING.matcher(line);
            if (m != null && m.matches()) {
                if (!body.isEmpty() || level > 0) {
                    sections.add(new Section(heading, anchor(heading), level, body.toString().strip()));
                }
                heading = m.group(2);
                level = m.group(1).length();
                if (title == null) {
                    title = heading;
                }
                body.setLength(0);
            } else {
                body.append(line).append('\n');
            }
        }
        sections.add(new Section(heading, anchor(heading), level, body.toString().strip()));
        return new Doc(path, title == null ? path : title, text, List.copyOf(sections));
    }

    /** GitHub's heading anchors: lower case, punctuation dropped, spaces to hyphens. */
    static String anchor(String heading) {
        return heading.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N} _-]", "")
                .strip()
                .replace(' ', '-');
    }

    private static String snippet(String text, String term) {
        var flat = text.replaceAll("\\s+", " ");
        var at = flat.toLowerCase(Locale.ROOT).indexOf(term);
        var from = Math.max(0, at - 100);
        var to = Math.min(flat.length(), Math.max(at, 0) + 200);
        return (from > 0 ? "…" : "") + flat.substring(from, to) + (to < flat.length() ? "…" : "");
    }

    private static int count(String haystack, String needle) {
        var n = 0;
        for (var i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private static String normalise(String path) {
        var p = path.strip().replace('\\', '/');
        p = p.startsWith("docs/") ? p.substring("docs/".length()) : p;
        return p.startsWith("/") ? p.substring(1) : p;
    }

    private static @Nullable String relativePath(Resource resource) throws IOException {
        var url = resource.getURL().toString();
        var at = url.lastIndexOf(ROOT + "/");
        if (at < 0 || url.endsWith("/")) {
            return null;
        }
        return url.substring(at + ROOT.length() + 1);
    }

    private static Yaml yaml() {
        var options = new LoaderOptions();
        options.setCodePointLimit(8 * 1024 * 1024);
        return new Yaml(new SafeConstructor(options));
    }
}
