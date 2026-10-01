package ca.northline.search.application;

/**
 * The public search endpoints are open to anonymous callers, so each client address gets {@code
 * northline.search.rate-limit} requests per minute (per api instance) before a 429.
 */
@FunctionalInterface
public interface SearchRateLimit {
    boolean allow(String clientAddress);
}
