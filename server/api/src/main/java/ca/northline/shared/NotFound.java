package ca.northline.shared;

import lombok.Getter;

/** The addressed resource does not exist (or is not visible to the caller). Rendered as HTTP 404 ProblemDetail. */
@Getter
public final class NotFound extends RuntimeException {
    private final String resource;
    private final String id;

    public NotFound(String resource, String id) {
        super("No %s with id %s".formatted(resource, id));
        this.resource = resource;
        this.id = id;
    }
}
