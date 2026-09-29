package ca.northline.merchants.application;

import ca.northline.merchants.domain.BusinessStructure;
import ca.northline.shared.RuleViolation.Violation;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates {@code merchants.merchants.legal_details} against the branch of docs/spec/legal-details.schema.json that
 * matches the business structure — the spec file itself, packaged on the classpath as
 * {@code spec/legal-details.schema.json}, is the rule set. Supports the keywords that file uses ({@code $ref},
 * {@code type}, {@code properties}, {@code required}, {@code additionalProperties}, {@code if/then}, {@code const},
 * {@code enum}, {@code pattern}, {@code format: date}, {@code minLength}, {@code maxLength}).
 *
 * <p>Messages are the per-field "required" family of validation-rules.md › Business step; one violation per field,
 * reported as {@code legalDetails.<property>}.
 */
@Component
public class LegalDetailsSchema {
    public static final String RESOURCE = "spec/legal-details.schema.json";
    public static final String FIELD = "legalDetails";

    public static final String REQUIRED = "This is required.";
    public static final String DOC_REQUIRED = "Upload the document.";
    public static final String ADDRESS = "Enter the full address.";
    public static final String MIN_2 = "At least 2 characters.";
    public static final String DATE = "Use the format YYYY-MM-DD.";
    public static final String OPTION = "Pick one of the options.";
    public static final String SIN = "Stripe collects the SIN — confirm to continue.";
    public static final String UNKNOWN = "Not a field for this structure.";
    public static final String INVALID = "Check this field.";
    public static final Map<String, String> PATTERN_MESSAGES = Map.of(
            "business_number", "Business number is 9 digits (e.g. 123456789).",
            "alberta_corporate_access_number", "Corporate access number is 10 digits.",
            "corporations_canada_number", "Corporation number is 7 digits.",
            "cra_charity_number", "Format is 9 digits + RR0001 (e.g. 123456789 RR0001).");

    /** Number fields where spaces are typing aids ("123 456 789"); stripped and upper-cased before validation. */
    private static final Set<String> COMPACT = PATTERN_MESSAGES.keySet();

    private final JsonMapper json = JsonMapper.builder().build();
    private final JsonNode root;

    public LegalDetailsSchema() {
        try (InputStream in = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(RESOURCE), RESOURCE + " not on classpath")) {
            root = json.readTree(in);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Trims strings, drops blanks, compacts number fields and sets {@code structure}. */
    public Map<String, Object> normalize(BusinessStructure structure, Map<String, Object> details) {
        var out = new LinkedHashMap<String, Object>();
        details.forEach((key, value) -> {
            var v = normalizeValue(key, value);
            if (v != null) {
                out.put(key, v);
            }
        });
        out.put("structure", structure.code());
        return out;
    }

    /** Validates normalized details; empty when valid. */
    public List<Violation> validate(BusinessStructure structure, Map<String, Object> details) {
        var def = resolve(root.path("$defs").path(structure.code()));
        var out = new ArrayList<Violation>();
        checkObject(def, json.valueToTree(details), FIELD, out);
        return out;
    }

    /** Property names of the structure's branch (for the documents check). */
    public Set<String> documentFields(BusinessStructure structure) {
        var def = resolve(root.path("$defs").path(structure.code()));
        var out = new java.util.LinkedHashSet<String>();
        def.path("properties").properties().forEach(e -> {
            if (isDoc(e.getValue())) {
                out.add(e.getKey());
            }
        });
        return out;
    }

    private void checkObject(JsonNode schema, JsonNode value, String path, List<Violation> out) {
        if (!value.isObject()) {
            out.add(new Violation(path, "type", INVALID));
            return;
        }
        var props = schema.path("properties");
        var required = new ArrayList<String>();
        schema.path("required").forEach(r -> required.add(r.asString()));
        var cond = schema.path("if");
        if (!cond.isMissingNode()) {
            var condMet = true;
            for (var r : cond.path("required")) {
                condMet &= value.has(r.asString());
            }
            if (condMet) {
                schema.path("then").path("required").forEach(r -> required.add(r.asString()));
            }
        }
        for (var name : required) {
            if (!value.has(name)) {
                var prop = resolve(props.path(name));
                out.add(new Violation(
                        path + "." + name,
                        "required",
                        isDoc(prop) ? DOC_REQUIRED : prop.has("const") ? SIN : REQUIRED));
            }
        }
        value.properties().forEach(e -> {
            var name = e.getKey();
            var at = path + "." + name;
            if (!props.has(name)) {
                if (schema.path("additionalProperties").isBoolean()
                        && !schema.path("additionalProperties").asBoolean()) {
                    out.add(new Violation(at, "unknown", UNKNOWN));
                }
                return;
            }
            var prop = resolve(props.path(name));
            if ("object".equals(prop.path("type").asString(""))) {
                checkObject(prop, e.getValue(), at, out);
            } else {
                checkValue(name, prop, e.getValue(), at, out);
            }
        });
    }

    private void checkValue(String name, JsonNode schema, JsonNode value, String path, List<Violation> out) {
        if (schema.has("const")) {
            if (!schema.get("const").equals(value)) {
                out.add(new Violation(path, "required", name.equals("structure") ? INVALID : SIN));
            }
            return;
        }
        if (schema.has("enum")) {
            var ok = false;
            for (var option : schema.get("enum")) {
                ok |= option.equals(value);
            }
            if (!ok) {
                out.add(new Violation(path, "enum", OPTION));
            }
            return;
        }
        var type = schema.path("type").asString("");
        if (type.equals("boolean")) {
            if (!value.isBoolean()) {
                out.add(new Violation(path, "type", OPTION));
            }
            return;
        }
        if (!value.isString()) {
            out.add(new Violation(path, "type", INVALID));
            return;
        }
        var text = value.asString();
        if (schema.has("minLength") && text.length() < schema.get("minLength").asInt()) {
            out.add(new Violation(path, "length", schema.get("minLength").asInt() >= 5 ? ADDRESS : MIN_2));
        } else if (schema.has("maxLength")
                && text.length() > schema.get("maxLength").asInt()) {
            out.add(new Violation(
                    path,
                    "length",
                    "At most %d characters.".formatted(schema.get("maxLength").asInt())));
        } else if (schema.has("pattern")
                && !Pattern.compile(schema.get("pattern").asString())
                        .matcher(text)
                        .find()) {
            out.add(new Violation(
                    path, "format", isDoc(schema) ? DOC_REQUIRED : PATTERN_MESSAGES.getOrDefault(name, INVALID)));
        } else if ("date".equals(schema.path("format").asString("")) && !isDate(text)) {
            out.add(new Violation(path, "format", DATE));
        }
    }

    private JsonNode resolve(JsonNode schema) {
        var ref = schema.path("$ref");
        // Sibling keywords next to a $ref in this file are descriptions only, so the target's rules apply.
        return ref.isString() && ref.asString().startsWith("#/$defs/")
                ? root.path("$defs").path(ref.asString().substring("#/$defs/".length()))
                : schema;
    }

    private boolean isDoc(JsonNode prop) {
        return prop.equals(root.path("$defs").path("doc"))
                || "#/$defs/doc".equals(prop.path("$ref").asString(""));
    }

    private static boolean isDate(String text) {
        try {
            LocalDate.parse(text);
            return true;
        } catch (DateTimeParseException _) {
            return false;
        }
    }

    private static @org.jspecify.annotations.Nullable Object normalizeValue(
            String key, @org.jspecify.annotations.Nullable Object value) {
        if (value instanceof String s) {
            var t = s.strip();
            if (COMPACT.contains(key)) {
                t = t.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
            }
            return t.isEmpty() ? null : t;
        }
        if (value instanceof Map<?, ?> m) {
            var out = new LinkedHashMap<String, Object>();
            m.forEach((k, v) -> {
                var n = normalizeValue(String.valueOf(k), v);
                if (n != null) {
                    out.put(String.valueOf(k), n);
                }
            });
            return out.isEmpty() ? null : out;
        }
        return value;
    }
}
