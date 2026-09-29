package ca.northline.shared;

import java.util.List;
import lombok.Getter;

/**
 * A business/validation rule was broken. Rendered as HTTP 422 {@code {"errors":[{field, rule, message}]}}.
 *
 * <p>Throw it from domain code (value objects, aggregates) or application services when a rule from
 * {@code docs/spec/validation-rules.md} fails outside Bean Validation, e.g. a trimmed name that is too short:
 *
 * <pre>{@code
 * throw RuleViolation.of("displayName", "length", "At least 2 characters.");
 * }</pre>
 */
@Getter
public final class RuleViolation extends RuntimeException {

    /** One broken rule. {@code field} is the JSON property path of the request ({@code lines[2].amount}). */
    public record Violation(String field, String rule, String message) {}

    private final List<Violation> violations;

    public RuleViolation(List<Violation> violations) {
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("RuleViolation needs at least one violation");
        }
        super(violations.getFirst().message());
        this.violations = List.copyOf(violations);
    }

    public static RuleViolation of(String field, String rule, String message) {
        return new RuleViolation(List.of(new Violation(field, rule, message)));
    }
}
