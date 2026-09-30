package ca.northline.worker.events;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The event JSON Schemas ({@code server/api/src/main/resources/events/<type>.v<version>.schema.json}, copied onto the
 * worker's classpath at build time) by type and version, with a validator for the keywords they use. Like
 * {@code merchants.application.LegalDetailsSchema} in the api, it implements only that subset — and refuses to load a
 * schema that uses anything else, so a new keyword fails the build instead of being silently ignored.
 */
public final class EventSchemas {

    public static final String LOCATION = "classpath*:events/*.schema.json";

    /** The JSON Schema keywords this validator implements; S-34's contract check refuses schemas using others. */
    public static final Set<String> KEYWORDS = Set.of(
            "$schema",
            "$id",
            "title",
            "description",
            "type",
            "required",
            "properties",
            "additionalProperties",
            "enum",
            "pattern",
            "format",
            "minimum",
            "minLength",
            "maxLength",
            "items");

    /** Values of {@code format} the validator checks (S-34 refuses others: they would pass unchecked). */
    public static final Set<String> FORMATS = Set.of("date-time", "date");

    private static final Pattern ID = Pattern.compile("northline:([a-z0-9_.]+):(\\d+)");

    private final Map<String, JsonNode> schemas;
    private final Map<String, Pattern> patterns = new ConcurrentHashMap<>();

    private EventSchemas(Map<String, JsonNode> schemas) {
        this.schemas = Map.copyOf(schemas);
    }

    public static EventSchemas fromClasspath(JsonMapper json) {
        return fromClasspath(json, LOCATION);
    }

    /** Schemas in the same format elsewhere, e.g. the public webhook payloads ({@code classpath*:webhooks/…}, S-33). */
    public static EventSchemas fromClasspath(JsonMapper json, String location) {
        var schemas = new HashMap<String, JsonNode>();
        try {
            for (var resource : new PathMatchingResourcePatternResolver().getResources(location)) {
                try (var in = resource.getInputStream()) {
                    var schema = json.readTree(in);
                    var id = ID.matcher(schema.path("$id").asString(""));
                    if (!id.matches()) {
                        throw new IllegalStateException(resource + ": $id must be northline:<type>:<version>");
                    }
                    checkKeywords(schema, resource.getFilename() + " ");
                    schemas.put(key(id.group(1), Integer.parseInt(id.group(2))), schema);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (schemas.isEmpty()) {
            throw new IllegalStateException("No schemas on the classpath (" + location + ")");
        }
        return new EventSchemas(schemas);
    }

    /**
     * A validator over schemas read elsewhere — S-34's contract check reads the working tree's files. Same rules as
     * {@link #fromClasspath}: {@code $id} = {@code northline:<type>:<version>}, only {@link #KEYWORDS}.
     */
    public static EventSchemas of(Map<String, JsonNode> schemasByName) {
        var schemas = new HashMap<String, JsonNode>();
        schemasByName.forEach((name, schema) -> {
            var id = ID.matcher(schema.path("$id").asString(""));
            if (!id.matches()) {
                throw new IllegalStateException(name + ": $id must be northline:<type>:<version>");
            }
            checkKeywords(schema, name + " ");
            schemas.put(key(id.group(1), Integer.parseInt(id.group(2))), schema);
        });
        return new EventSchemas(schemas);
    }

    /** Keywords of {@code schema} (nested ones as {@code <property>.<keyword>}) outside {@link #KEYWORDS}. */
    public static List<String> unsupportedKeywords(JsonNode schema) {
        var found = new ArrayList<String>();
        collectUnsupported(schema, "", found);
        return List.copyOf(found);
    }

    private static void collectUnsupported(JsonNode schema, String where, List<String> found) {
        for (var name : schema.propertyNames()) {
            if (!KEYWORDS.contains(name)) {
                found.add(where + name);
            }
        }
        var properties = schema.get("properties");
        if (properties != null && properties.isObject()) {
            for (var name : properties.propertyNames()) {
                collectUnsupported(properties.get(name), where + name + ".", found);
            }
        }
        var items = schema.get("items");
        if (items != null && items.isObject()) {
            collectUnsupported(items, where + "items.", found);
        }
    }

    public boolean knows(String type, int version) {
        return schemas.containsKey(key(type, version));
    }

    public Set<String> keys() {
        return schemas.keySet();
    }

    /** Problems of {@code payload} against the schema of {@code type} v{@code version}; empty when it is valid. */
    public Optional<List<String>> validate(String type, int version, JsonNode payload) {
        var schema = schemas.get(key(type, version));
        if (schema == null) {
            return Optional.empty();
        }
        var problems = new ArrayList<String>();
        check(schema, payload, "$", problems);
        return Optional.of(List.copyOf(problems));
    }

    private static String key(String type, int version) {
        return type + ":" + version;
    }

    private static void checkKeywords(JsonNode schema, String where) {
        for (var name : schema.propertyNames()) {
            if (!KEYWORDS.contains(name)) {
                throw new IllegalStateException(
                        where + "uses the unsupported keyword " + name + " (EventSchemas implements " + KEYWORDS + ")");
            }
        }
        var properties = schema.get("properties");
        if (properties != null) {
            for (var name : properties.propertyNames()) {
                checkKeywords(properties.get(name), where + name + ".");
            }
        }
        var items = schema.get("items");
        if (items != null) {
            checkKeywords(items, where + "items.");
        }
    }

    private void check(JsonNode schema, JsonNode value, String path, List<String> problems) {
        var type = schema.get("type");
        if (type != null && !matchesType(type, value)) {
            problems.add(path + " must be " + type);
            return;
        }
        var allowed = schema.get("enum");
        if (allowed != null && allowed.valueStream().noneMatch(value::equals)) {
            problems.add(path + " must be one of " + allowed);
        }
        if (value.isString()) {
            var text = value.asString();
            var pattern = schema.get("pattern");
            if (pattern != null
                    && !patterns.computeIfAbsent(pattern.asString(), Pattern::compile)
                            .matcher(text)
                            .find()) {
                problems.add(path + " must match " + pattern.asString());
            }
            if ("date-time".equals(schema.path("format").asString("")) && !isDateTime(text)) {
                problems.add(path + " must be an RFC 3339 date-time");
            }
            if ("date".equals(schema.path("format").asString("")) && !isDate(text)) {
                problems.add(path + " must be an RFC 3339 full-date");
            }
            var length = text.codePointCount(0, text.length());
            if (schema.has("minLength") && length < schema.get("minLength").asInt()) {
                problems.add(
                        path + " is shorter than " + schema.get("minLength").asInt());
            }
            if (schema.has("maxLength") && length > schema.get("maxLength").asInt()) {
                problems.add(path + " is longer than " + schema.get("maxLength").asInt());
            }
        }
        if (value.isNumber()
                && schema.has("minimum")
                && value.asDouble() < schema.get("minimum").asDouble()) {
            problems.add(path + " must be ≥ " + schema.get("minimum"));
        }
        if (value.isObject()) {
            checkObject(schema, value, path, problems);
        }
        var items = schema.get("items");
        if (value.isArray() && items != null) {
            for (var i = 0; i < value.size(); i++) {
                check(items, value.get(i), path + "[" + i + "]", problems);
            }
        }
    }

    private void checkObject(JsonNode schema, JsonNode value, String path, List<String> problems) {
        var required = schema.get("required");
        if (required != null) {
            required.valueStream()
                    .map(JsonNode::asString)
                    .filter(name -> !value.has(name))
                    .forEach(name -> problems.add(path + "." + name + " is required"));
        }
        var properties = schema.get("properties");
        for (var name : value.propertyNames()) {
            var property = properties == null ? null : properties.get(name);
            if (property != null) {
                check(property, value.get(name), path + "." + name, problems);
            } else if (schema.path("additionalProperties").isBoolean()
                    && !schema.get("additionalProperties").asBoolean()) {
                problems.add(path + "." + name + " is not allowed");
            }
        }
    }

    private static boolean matchesType(JsonNode type, JsonNode value) {
        if (type.isArray()) {
            return type.valueStream().anyMatch(t -> matchesType(t, value));
        }
        return switch (type.asString()) {
            case "string" -> value.isString();
            case "integer" ->
                value.isIntegralNumber() || (value.isNumber() && value.asDouble() == Math.rint(value.asDouble()));
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "null" -> value.isNull();
            default -> throw new IllegalStateException("Unknown JSON Schema type " + type);
        };
    }

    private static boolean isDate(String text) {
        try {
            java.time.LocalDate.parse(text);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean isDateTime(String text) {
        try {
            OffsetDateTime.parse(text);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
