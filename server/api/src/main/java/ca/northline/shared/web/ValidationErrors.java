package ca.northline.shared.web;

import ca.northline.shared.RuleViolation.Violation;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The 422 body {@code {"errors":[{field, rule, message}]}} and the mapping from Bean Validation constraint names to
 * rule ids. One error per field — the most basic failing rule wins (required before format before length…), which is
 * what the UI shows under the field.
 */
record ValidationErrors(List<Violation> errors) {

    private static final Map<String, String> RULES = Map.ofEntries(
            Map.entry("NotNull", "required"),
            Map.entry("NotBlank", "required"),
            Map.entry("NotEmpty", "required"),
            Map.entry("AssertTrue", "required"),
            Map.entry("Pattern", "format"),
            Map.entry("Email", "format"),
            Map.entry("Size", "length"),
            Map.entry("Length", "length"),
            Map.entry("Min", "range"),
            Map.entry("Max", "range"),
            Map.entry("DecimalMin", "range"),
            Map.entry("DecimalMax", "range"),
            Map.entry("Positive", "range"),
            Map.entry("PositiveOrZero", "range"),
            Map.entry("Negative", "range"),
            Map.entry("NegativeOrZero", "range"),
            Map.entry("Range", "range"));

    private static final List<String> PRIORITY = List.of("required", "format", "length", "range");
    private static final Pattern CAMEL = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

    /** Rule id for a constraint annotation's simple name: built-ins per {@link #RULES}, custom ones snake_cased. */
    static String ruleFor(String constraint) {
        return RULES.getOrDefault(
                constraint, CAMEL.matcher(constraint).replaceAll("_").toLowerCase(Locale.ROOT));
    }

    static ValidationErrors of(Collection<Violation> violations) {
        var byField = new LinkedHashMap<String, Violation>();
        violations.stream()
                .sorted(Comparator.comparingInt(ValidationErrors::rank))
                .forEach(v -> byField.putIfAbsent(v.field(), v));
        return new ValidationErrors(byField.values().stream()
                .sorted(Comparator.comparing(Violation::field))
                .toList());
    }

    private static int rank(Violation v) {
        int i = PRIORITY.indexOf(v.rule());
        return i < 0 ? PRIORITY.size() : i;
    }
}
