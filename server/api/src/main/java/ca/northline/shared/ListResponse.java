package ca.northline.shared;

import java.util.List;

/**
 * Collection responses are always wrapped: {@code {"items":[…]}} — leaves room for {@code nextCursor}/totals without a
 * breaking change.
 */
public record ListResponse<T>(List<T> items) {
    public ListResponse {
        items = List.copyOf(items);
    }
}
