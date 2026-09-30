package ca.northline.contracts;

import ca.northline.worker.events.EventSchemas;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import tools.jackson.databind.JsonNode;

/**
 * Check 1: a schema is valid JSON Schema (draft 2020-12) <b>for the subset the worker's {@link EventSchemas}
 * implements</b> — only its keywords, each with a value of the right shape — so no rule in a file is silently skipped
 * by the consumers. Also: {@code $id} = {@code northline:<type>:<version>} matching the file name, the root is an object
 * with {@code eventId}, {@code occurredAt}, {@code aggregateId} required, and every required field is declared.
 */
public final class SchemaRules {

    static final String DRAFT = "https://json-schema.org/draft/2020-12/schema";
    static final Set<String> TYPES = Set.of("string", "integer", "number", "boolean", "object", "array", "null");
    static final List<String> ENVELOPE = List.of("eventId", "occurredAt", "aggregateId");

    private SchemaRules() {}

    public static List<String> problems(String fileName, JsonNode schema) {
        var problems = new ArrayList<String>();
        var name = SchemaFiles.NAME.matcher(fileName);
        if (!name.matches()) {
            problems.add("file name must be <module>.<event>.v<version>.schema.json");
        }
        if (!schema.isObject()) {
            problems.add("the schema is not a JSON object");
            return problems;
        }
        if (!DRAFT.equals(schema.path("$schema").asString(""))) {
            problems.add("$schema must be " + DRAFT);
        }
        var expectedId = name.matches() ? "northline:" + name.group(1) + ":" + name.group(2) : null;
        if (expectedId != null && !expectedId.equals(schema.path("$id").asString(""))) {
            problems.add("$id must be " + expectedId + " (was " + schema.path("$id") + ")");
        }
        EventSchemas.unsupportedKeywords(schema)
                .forEach(k -> problems.add("uses " + k + ", which the worker's EventSchemas doesn't implement "
                        + "(supported: " + EventSchemas.KEYWORDS + ")"));
        if (!"object".equals(schema.path("type").asString(""))) {
            problems.add("the root type must be \"object\"");
        }
        var required = texts(schema.get("required"));
        for (var field : ENVELOPE) {
            if (!required.contains(field)) {
                problems.add("the envelope field " + field + " must be required");
            }
        }
        shape(schema, "$", problems);
        return problems;
    }

    private static void shape(JsonNode s, String at, List<String> problems) {
        if (!s.isObject()) {
            problems.add(at + " must be a schema object");
            return;
        }
        var type = s.get("type");
        if (type != null) {
            var types = type.isArray() ? texts(type) : type.isString() ? List.of(type.asString()) : List.<String>of();
            if (types.isEmpty() || !TYPES.containsAll(types)) {
                problems.add(at + ".type must be one of " + TYPES + " or an array of them");
            }
        }
        var required = s.get("required");
        if (required != null) {
            if (!required.isArray() || required.valueStream().anyMatch(v -> !v.isString())) {
                problems.add(at + ".required must be an array of names");
            } else {
                for (var field : texts(required)) {
                    if (!s.path("properties").has(field)) {
                        problems.add(at + ".required names " + field + ", which properties doesn't declare");
                    }
                }
            }
        }
        var properties = s.get("properties");
        if (properties != null) {
            if (!properties.isObject()) {
                problems.add(at + ".properties must be an object");
            } else {
                for (var field : properties.propertyNames()) {
                    shape(properties.get(field), at + "." + field, problems);
                }
            }
        }
        var additional = s.get("additionalProperties");
        if (additional != null && !additional.isBoolean()) {
            problems.add(at + ".additionalProperties must be true or false (schemas there are not implemented)");
        }
        var allowed = s.get("enum");
        if (allowed != null && (!allowed.isArray() || allowed.isEmpty())) {
            problems.add(at + ".enum must be a non-empty array");
        }
        var pattern = s.get("pattern");
        if (pattern != null) {
            try {
                Pattern.compile(pattern.asString());
            } catch (PatternSyntaxException e) {
                problems.add(at + ".pattern is not a valid regular expression: " + e.getDescription());
            }
        }
        var format = s.get("format");
        if (format != null && !EventSchemas.FORMATS.contains(format.asString(""))) {
            problems.add(
                    at + ".format " + format + " isn't implemented by the worker (only " + EventSchemas.FORMATS + ")");
        }
        for (var bound : List.of("minLength", "maxLength")) {
            if (s.has(bound)
                    && !(s.get(bound).isIntegralNumber() && s.get(bound).asInt() >= 0)) {
                problems.add(at + "." + bound + " must be a non-negative integer");
            }
        }
        if (s.has("minimum") && !s.get("minimum").isNumber()) {
            problems.add(at + ".minimum must be a number");
        }
        var items = s.get("items");
        if (items != null) {
            shape(items, at + "[]", problems);
        }
    }

    static List<String> texts(@org.jspecify.annotations.Nullable JsonNode array) {
        return array == null || !array.isArray()
                ? List.of()
                : array.valueStream()
                        .filter(JsonNode::isString)
                        .map(JsonNode::asString)
                        .toList();
    }
}
