package ca.northline.merchants.application;

import ca.northline.merchants.application.LegalForm.Field;
import ca.northline.merchants.application.LegalForm.Kind;
import ca.northline.merchants.domain.BusinessStructure;
import ca.northline.merchants.domain.LegacyLegalDetails;
import ca.northline.region.api.LegalForms;
import ca.northline.region.api.LegalForms.Condition;
import ca.northline.region.api.LegalForms.FieldRule;
import ca.northline.shared.RuleViolation.Violation;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The legal details of the Business step: the branch of docs/spec/legal-details.schema.json that matches the business
 * structure (packaged on the classpath as {@code spec/legal-details.schema.json}) with the overlays of the business's
 * province from the region model ({@link LegalForms}, {@code region.legal_forms}) — merged into a {@link LegalForm},
 * which the api serves to the Studio and validates {@code merchants.merchants.legal_details} against. Nothing here names
 * a province: which provincial identifiers are required, their labels, formats and help are region data.
 *
 * <p>Messages are the per-field "required" family of validation-rules.md › Business step; one violation per field,
 * reported as {@code legalDetails.<property>}. A format the province defines has the province's message.
 */
@Slf4j
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
    /** The federal identifiers' formats (the same in every province). */
    public static final Map<String, String> PATTERN_MESSAGES = Map.of(
            "business_number", "Business number is 9 digits (e.g. 123456789).",
            "corporations_canada_number", "Corporation number is 7 digits.",
            "cra_charity_number", "Format is 9 digits + RR0001 (e.g. 123456789 RR0001).");

    /** Home-jurisdiction values read from rows written before every province was offered; accepted, not offered. */
    static final Set<String> LEGACY_OPTIONS = Set.of("OTHER_CA");

    private static final String HOME_JURISDICTION = "home_jurisdiction";
    private static final String TRADE_NAME_REGISTRATION = "trade_name_registration";

    private final LegalForms regionForms;
    private final JsonNode root;
    private final Map<BusinessStructure, List<Field>> base = new EnumMap<>(BusinessStructure.class);
    private final Map<String, Optional<Pattern>> patterns = new ConcurrentHashMap<>();

    public LegalDetailsSchema(LegalForms regionForms) {
        this.regionForms = regionForms;
        var json = JsonMapper.builder().build();
        try (InputStream in = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(RESOURCE), RESOURCE + " not on classpath")) {
            root = json.readTree(in);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        for (var structure : BusinessStructure.values()) {
            base.put(structure, fields(root.path("$defs").path(structure.code())));
        }
    }

    /** The structure's form in the province (the schema's generic form when the region model has no row). */
    public LegalForm form(BusinessStructure structure, @Nullable String province) {
        var row = regionForms.form(province, structure.code());
        return merge(structure, province, row.orElse(null));
    }

    /** Every structure's form in the province, in the schema's order. */
    public List<LegalForm> forms(@Nullable String province) {
        var rows = new LinkedHashMap<String, LegalForms.LegalForm>();
        regionForms.forms(province).forEach(r -> rows.put(r.structure(), r));
        return Arrays.stream(BusinessStructure.values())
                .map(s -> merge(s, province, rows.get(s.code())))
                .toList();
    }

    /** Old keys renamed, strings trimmed, blanks dropped, compact fields compacted, {@code structure} set. */
    public Map<String, Object> normalize(LegalForm form, Map<String, Object> details) {
        var compact = new LinkedHashSet<String>();
        form.fields().stream().filter(Field::compact).forEach(f -> compact.add(f.key()));
        var out = new LinkedHashMap<String, Object>();
        LegacyLegalDetails.upgrade(details).forEach((key, value) -> {
            var v = normalizeValue(compact.contains(key), value);
            if (v != null) {
                out.put(key, v);
            }
        });
        out.put("structure", form.structure().code());
        return out;
    }

    /** Validates normalized details against the form; empty when valid. */
    public List<Violation> validate(LegalForm form, Map<String, Object> details) {
        var out = new ArrayList<Violation>();
        for (var field : form.fields()) {
            check(field, details, FIELD, out);
        }
        details.keySet().stream()
                .filter(k -> !k.equals("structure") && form.field(k).isEmpty())
                .forEach(k -> out.add(new Violation(FIELD + "." + k, "unknown", UNKNOWN)));
        return out;
    }

    /** Document fields of the structure (for the documents check). */
    public Set<String> documentFields(BusinessStructure structure) {
        var out = new LinkedHashSet<String>();
        baseFields(structure).stream().filter(f -> f.kind() == Kind.DOCUMENT).forEach(f -> out.add(f.key()));
        return out;
    }

    // ------------------------------------------------------------------------------------------------- the merge

    private List<Field> baseFields(BusinessStructure structure) {
        return Objects.requireNonNull(base.get(structure), structure::code);
    }

    private LegalForm merge(
            BusinessStructure structure, @Nullable String province, LegalForms.@Nullable LegalForm row) {
        var tradeName = row == null || row.tradeNameRegistration();
        var fields = baseFields(structure).stream()
                .map(f -> overlay(f, row == null ? null : row.fields().get(f.key()), province, tradeName))
                .toList();
        return new LegalForm(
                structure,
                province,
                row == null ? null : row.registry(),
                row == null ? null : row.registryUrl(),
                row == null ? null : row.law(),
                tradeName,
                row != null && row.verified(),
                row == null ? List.of("registry", "law", "identifier") : row.unverified(),
                fields);
    }

    private Field overlay(Field f, @Nullable FieldRule rule, @Nullable String province, boolean tradeName) {
        var required = f.required();
        var when = f.requiredWhen();
        if (f.key().equals(TRADE_NAME_REGISTRATION) && !tradeName) {
            required = false;
            when = null;
        }
        var options = f.options();
        if (f.key().equals(HOME_JURISDICTION)) {
            var own = province == null ? "" : province.strip().toUpperCase(Locale.ROOT);
            options = f.accepted().stream()
                    .filter(o -> !o.equals(own) && !LEGACY_OPTIONS.contains(o))
                    .toList();
        }
        if (rule == null) {
            return withOptions(f, required, when, options);
        }
        if (rule.required() != null || rule.requiredWhen() != null) {
            required = Boolean.TRUE.equals(rule.required());
            when = rule.requiredWhen();
        }
        var pattern = rule.pattern() != null && compiled(rule.pattern()).isPresent() ? rule.pattern() : f.pattern();
        return new Field(
                f.key(),
                f.kind(),
                required,
                when,
                rule.label() != null ? rule.label() : f.label(),
                rule.help() != null ? rule.help() : f.help(),
                rule.example() != null ? rule.example() : f.example(),
                pattern,
                rule.pattern() != null && pattern != null && pattern.equals(rule.pattern())
                        ? rule.patternMessage()
                        : null,
                f.minLength(),
                f.maxLength(),
                f.compact() || (f.kind() == Kind.TEXT && pattern != null),
                options,
                f.accepted(),
                f.parts());
    }

    private static Field withOptions(Field f, boolean required, @Nullable Condition when, List<String> options) {
        return new Field(
                f.key(),
                f.kind(),
                required,
                when,
                f.label(),
                f.help(),
                f.example(),
                f.pattern(),
                f.patternMessage(),
                f.minLength(),
                f.maxLength(),
                f.compact(),
                options,
                f.accepted(),
                f.parts());
    }

    // ------------------------------------------------------------------------------------------------- the schema

    private List<Field> fields(JsonNode def) {
        var required = strings(def.path("required"));
        var conditions = new LinkedHashMap<String, Condition>();
        var cond = strings(def.path("if").path("required"));
        if (cond.size() == 1) {
            for (var key : strings(def.path("then").path("required"))) {
                conditions.put(key, new Condition(cond.getFirst(), List.of(), List.of()));
            }
        }
        var out = new ArrayList<Field>();
        def.path("properties").properties().forEach(e -> {
            if (!e.getKey().equals("structure")) {
                out.add(field(e.getKey(), e.getValue(), required.contains(e.getKey()), conditions.get(e.getKey())));
            }
        });
        return List.copyOf(out);
    }

    private Field field(String key, JsonNode schema, boolean required, @Nullable Condition when) {
        var doc = "#/$defs/doc".equals(schema.path("$ref").asString(""));
        var address = "#/$defs/address".equals(schema.path("$ref").asString(""));
        var s = resolve(schema);
        Kind kind;
        if (doc) {
            kind = Kind.DOCUMENT;
        } else if (address) {
            kind = Kind.ADDRESS;
        } else if (s.has("const")) {
            kind = Kind.STRIPE_CONFIRMATION;
        } else if (s.has("enum")) {
            kind = Kind.CHOICE;
        } else if ("boolean".equals(s.path("type").asString(""))) {
            kind = Kind.YES_NO;
        } else if ("object".equals(s.path("type").asString(""))) {
            kind = Kind.GROUP;
        } else if ("date".equals(s.path("format").asString(""))) {
            kind = Kind.DATE;
        } else {
            kind = Kind.TEXT;
        }
        var pattern = s.path("pattern").isString() ? s.path("pattern").asString() : null;
        var options = strings(s.path("enum"));
        var parts = kind == Kind.GROUP ? fields(s) : List.<Field>of();
        return new Field(
                key,
                kind,
                required,
                when,
                null,
                null,
                null,
                pattern,
                null,
                s.has("minLength") ? s.get("minLength").asInt() : null,
                s.has("maxLength") ? s.get("maxLength").asInt() : null,
                kind == Kind.TEXT && pattern != null,
                options,
                options,
                parts);
    }

    private JsonNode resolve(JsonNode schema) {
        var ref = schema.path("$ref");
        // Sibling keywords next to a $ref in this file are descriptions only, so the target's rules apply.
        return ref.isString() && ref.asString().startsWith("#/$defs/")
                ? root.path("$defs").path(ref.asString().substring("#/$defs/".length()))
                : schema;
    }

    private static List<String> strings(JsonNode array) {
        var out = new ArrayList<String>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }

    // ------------------------------------------------------------------------------------------------- validation

    private void check(Field field, Map<String, ?> values, String at, List<Violation> out) {
        var path = at + "." + field.key();
        var value = values.get(field.key());
        if (value == null) {
            var when = field.requiredWhen();
            if (field.required() || (when != null && when.holds(values.get(when.field())))) {
                out.add(new Violation(
                        path,
                        "required",
                        switch (field.kind()) {
                            case DOCUMENT -> DOC_REQUIRED;
                            case STRIPE_CONFIRMATION -> SIN;
                            default -> REQUIRED;
                        }));
            }
            return;
        }
        switch (field.kind()) {
            case STRIPE_CONFIRMATION -> {
                if (!Boolean.TRUE.equals(value)) {
                    out.add(new Violation(path, "required", SIN));
                }
            }
            case CHOICE -> {
                if (!(value instanceof String s) || !field.accepted().contains(s)) {
                    out.add(new Violation(path, "enum", OPTION));
                }
            }
            case YES_NO -> {
                if (!(value instanceof Boolean)) {
                    out.add(new Violation(path, "type", OPTION));
                }
            }
            case GROUP -> {
                if (value instanceof Map<?, ?> m) {
                    var parts = new LinkedHashMap<String, Object>();
                    m.forEach((k, v) -> parts.put(String.valueOf(k), v));
                    field.parts().forEach(p -> check(p, parts, path, out));
                } else {
                    out.add(new Violation(path, "type", INVALID));
                }
            }
            default -> checkText(field, value, path, out);
        }
    }

    private void checkText(Field field, Object value, String path, List<Violation> out) {
        if (!(value instanceof String text)) {
            out.add(new Violation(path, "type", INVALID));
            return;
        }
        var pattern = field.pattern() == null ? Optional.<Pattern>empty() : compiled(field.pattern());
        if (field.minLength() != null && text.length() < field.minLength()) {
            out.add(new Violation(path, "length", field.minLength() >= 5 ? ADDRESS : MIN_2));
        } else if (field.maxLength() != null && text.length() > field.maxLength()) {
            out.add(new Violation(path, "length", "At most %d characters.".formatted(field.maxLength())));
        } else if (pattern.isPresent() && !pattern.get().matcher(text).find()) {
            out.add(new Violation(path, "format", formatMessage(field)));
        } else if (field.kind() == Kind.DATE && !isDate(text)) {
            out.add(new Violation(path, "format", DATE));
        }
    }

    private static String formatMessage(Field field) {
        if (field.kind() == Kind.DOCUMENT) {
            return DOC_REQUIRED;
        }
        var province = field.patternMessage();
        return province != null ? province.en() : PATTERN_MESSAGES.getOrDefault(field.key(), INVALID);
    }

    private Optional<Pattern> compiled(String regex) {
        return patterns.computeIfAbsent(regex, r -> {
            try {
                return Optional.of(Pattern.compile(r));
            } catch (PatternSyntaxException e) {
                log.warn("region.legal_forms pattern {} does not compile; ignored: {}", r, e.getMessage());
                return Optional.empty();
            }
        });
    }

    private static boolean isDate(String text) {
        try {
            LocalDate.parse(text);
            return true;
        } catch (DateTimeParseException _) {
            return false;
        }
    }

    private static @Nullable Object normalizeValue(boolean compact, @Nullable Object value) {
        if (value instanceof String s) {
            var t = s.strip();
            if (compact) {
                t = t.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
            }
            return t.isEmpty() ? null : t;
        }
        if (value instanceof Map<?, ?> m) {
            var out = new LinkedHashMap<String, Object>();
            m.forEach((k, v) -> {
                var n = normalizeValue(false, v);
                if (n != null) {
                    out.put(String.valueOf(k), n);
                }
            });
            return out.isEmpty() ? null : out;
        }
        return value;
    }
}
