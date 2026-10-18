package ca.northline.searchindex;

import lombok.Getter;

/**
 * Meilisearch refused a request or a task failed: the HTTP status (0 for a failed task), and Meilisearch's error
 * {@code code} ({@code index_not_found}, {@code invalid_search_filter} …, docs.meilisearch.com/errors).
 */
@Getter
public final class MeilisearchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;

    public MeilisearchException(int status, String code, String message) {
        super("Meilisearch " + (status > 0 ? status + " " : "") + code + ": " + message);
        this.status = status;
        this.code = code;
    }
}
