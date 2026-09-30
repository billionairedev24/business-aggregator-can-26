package ca.northline.contracts;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Check 4: what a schema change breaks for consumers that validate against the old file (the worker loads every
 * version and rejects what doesn't match) or producers still running old code. A change to
 * {@code <type>.v<n>.schema.json} is <b>breaking</b> when it
 *
 * <ul>
 *   <li>removes a declared field, or makes a field newly required;
 *   <li>narrows a type (drops one of the allowed types, e.g. {@code ["string","null"]} → {@code "string"}, or
 *       {@code number} → {@code integer}), or narrows / adds an {@code enum};
 *   <li>closes the object ({@code additionalProperties} → {@code false});
 *   <li>tightens a constraint: adds or changes {@code pattern} / {@code format}, raises {@code minimum} /
 *       {@code minLength}, lowers or adds {@code maxLength};
 *   <li>deletes the file (events of that version may still sit in a topic or a DLQ).
 * </ul>
 *
 * The fix is a version bump: leave {@code v<n>} as it was, add {@code v<n+1>} and return it from the event's
 * {@code version()}. New files, new optional fields, wider types and enums, descriptions are not breaking.
 */
public final class BreakingChanges {

    private BreakingChanges() {}

    /** Breaking changes from {@code base} to {@code head} (file name → schema), one line each, by file. */
    public static Map<String, List<String>> between(Map<String, JsonNode> base, Map<String, JsonNode> head) {
        var found = new TreeMap<String, List<String>>();
        base.forEach((file, before) -> {
            var after = head.get(file);
            var problems = after == null
                    ? List.of("deleted — events of this version may still be in a topic or a DLQ; keep the file")
                    : compare(before, after);
            if (!problems.isEmpty()) {
                found.put(file, problems);
            }
        });
        return found;
    }

    public static List<String> compare(JsonNode before, JsonNode after) {
        var problems = new ArrayList<String>();
        compare(before, after, "$", problems);
        return problems;
    }

    private static void compare(JsonNode before, JsonNode after, String at, List<String> problems) {
        var wasTypes = types(before);
        var isTypes = types(after);
        if (!wasTypes.isEmpty()) {
            var lost = new LinkedHashSet<String>();
            for (var t : wasTypes) {
                if (!(isTypes.isEmpty()
                        || isTypes.contains(t)
                        || (t.equals("integer") && isTypes.contains("number")))) {
                    lost.add(t);
                }
            }
            if (!lost.isEmpty()) {
                problems.add(at + ": type narrowed from " + wasTypes + " to " + isTypes + " (" + lost
                        + " no longer allowed)");
            }
        } else if (!isTypes.isEmpty()) {
            problems.add(at + ": type restricted to " + isTypes);
        }
        var wasEnum = before.get("enum");
        var isEnum = after.get("enum");
        if (isEnum != null) {
            if (wasEnum == null) {
                problems.add(at + ": enum " + isEnum + " added");
            } else {
                var removed = new ArrayList<String>();
                wasEnum.valueStream()
                        .filter(v -> isEnum.valueStream().noneMatch(v::equals))
                        .forEach(v -> removed.add(v.toString()));
                if (!removed.isEmpty()) {
                    problems.add(at + ": enum narrowed, removed " + removed);
                }
            }
        }
        var newlyRequired = new ArrayList<>(SchemaRules.texts(after.get("required")));
        newlyRequired.removeAll(SchemaRules.texts(before.get("required")));
        newlyRequired.forEach(f -> problems.add(at + "." + f + ": newly required"));
        if (!closed(before) && closed(after)) {
            problems.add(at + ": additionalProperties set to false");
        }
        changed(before, after, "pattern").ifPresent(c -> problems.add(at + ": pattern " + c));
        changed(before, after, "format").ifPresent(c -> problems.add(at + ": format " + c));
        raised(before, after, "minimum").ifPresent(c -> problems.add(at + ": minimum " + c));
        raised(before, after, "minLength").ifPresent(c -> problems.add(at + ": minLength " + c));
        lowered(before, after, "maxLength").ifPresent(c -> problems.add(at + ": maxLength " + c));
        var wasProperties = before.path("properties");
        var isProperties = after.path("properties");
        for (var field : wasProperties.propertyNames()) {
            if (!isProperties.has(field)) {
                problems.add(at + "." + field + ": field removed");
            } else {
                compare(wasProperties.get(field), isProperties.get(field), at + "." + field, problems);
            }
        }
        var wasItems = before.get("items");
        var isItems = after.get("items");
        if (wasItems != null && isItems != null) {
            compare(wasItems, isItems, at + "[]", problems);
        } else if (wasItems == null && isItems != null) {
            problems.add(at + ": items constrained");
        }
    }

    private static Set<String> types(JsonNode schema) {
        var type = schema.get("type");
        if (type == null) {
            return Set.of();
        }
        return type.isArray() ? new LinkedHashSet<>(SchemaRules.texts(type)) : Set.of(type.asString());
    }

    private static boolean closed(JsonNode schema) {
        return schema.path("additionalProperties").isBoolean()
                && !schema.get("additionalProperties").asBoolean();
    }

    private static java.util.Optional<String> changed(JsonNode before, JsonNode after, String keyword) {
        var was = text(before.get(keyword));
        var is = text(after.get(keyword));
        if (is == null || Objects.equals(was, is)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(was == null ? "added (" + is + ")" : "changed from " + was + " to " + is);
    }

    private static java.util.Optional<String> raised(JsonNode before, JsonNode after, String keyword) {
        if (!after.has(keyword)) {
            return java.util.Optional.empty();
        }
        var is = after.get(keyword).asDouble();
        if (!before.has(keyword)) {
            return java.util.Optional.of("added (" + after.get(keyword) + ")");
        }
        return is > before.get(keyword).asDouble()
                ? java.util.Optional.of("raised from " + before.get(keyword) + " to " + after.get(keyword))
                : java.util.Optional.empty();
    }

    private static java.util.Optional<String> lowered(JsonNode before, JsonNode after, String keyword) {
        if (!after.has(keyword)) {
            return java.util.Optional.empty();
        }
        if (!before.has(keyword)) {
            return java.util.Optional.of("added (" + after.get(keyword) + ")");
        }
        return after.get(keyword).asDouble() < before.get(keyword).asDouble()
                ? java.util.Optional.of("lowered from " + before.get(keyword) + " to " + after.get(keyword))
                : java.util.Optional.empty();
    }

    private static @Nullable String text(@Nullable JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }
}
