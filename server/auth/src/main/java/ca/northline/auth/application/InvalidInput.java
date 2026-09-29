package ca.northline.auth.application;

import java.util.List;
import lombok.Getter;

/** A rule that needs the database or the flow state failed — answered as 422 {@code {errors:[{field,rule,message}]}}. */
@Getter
public final class InvalidInput extends RuntimeException {

    /** One broken rule; {@code field} is the JSON property of the request. */
    public record Violation(String field, String rule, String message) {}

    private final List<Violation> violations;

    public InvalidInput(List<Violation> violations) {
        super(violations.stream().map(Violation::message).reduce("", (a, b) -> (a + " " + b).trim()));
        this.violations = List.copyOf(violations);
    }

    public static InvalidInput of(String field, String rule, String message) {
        return new InvalidInput(List.of(new Violation(field, rule, message)));
    }
}
