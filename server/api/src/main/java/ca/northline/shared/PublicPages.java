package ca.northline.shared;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One section of the consumer site's sitemap (S-63, {@code GET /api/v1/public/sitemap}, aggregated by the
 * {@code discovery} module): the public pages a module owns. Implement it as a Spring bean in the owning module; only
 * pages a guest can open belong here (live, approved, of active businesses).
 *
 * <p>The web app turns {@link #section()} and {@link Page#key()} into its route ({@code providers} → {@code
 * /providers/<key>}, {@code kitchens} → {@code /food/<key>}, {@code products} → {@code /products/<key>}, {@code
 * services} → {@code /services/<key>}, {@code departments} → {@code /shop/<key>}). Keep both queries cheap and the order
 * stable (by key) so pages don't shift between requests.
 */
public interface PublicPages {

    /** The section's name: {@code providers}, {@code kitchens}, {@code products}, {@code services}, {@code departments}. */
    String section();

    long count();

    /** Pages {@code offset} … {@code offset + limit - 1} ordered by key. */
    List<Page> list(long offset, int limit);

    /**
     * @param key the route's parameter (a slug or an id)
     * @param customDomain a live custom domain serving this page instead ({@code book.example.ca}), or null
     * @param updatedAt when its content last changed, when known
     */
    record Page(
            String key,
            @Nullable String customDomain,
            @Nullable Instant updatedAt) {}
}
