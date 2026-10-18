package ca.northline.search.integration;

import ca.northline.searchindex.MeilisearchConnection;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.search.*} (docs/runbooks/search.md).
 *
 * @param provider {@code elasticsearch} (listings_en / listings_fr), {@code meilisearch} (the same indexes on Meilisearch,
 *     {@code meilisearch.*}) or {@code local} (no index; refused in staging/prod)
 * <p>The markets served, their zones and the default market are the region model's ({@code northline.region}, S-134).
 *
 * @param cacheTtl how long an identical search is answered from the hot-query cache
 * @param rateLimit requests per minute and client address (anonymous callers included)
 * @param rateLimitExempt S-119: addresses or CIDR ranges the rate limit never counts (load generators; refused in prod)
 * @param meilisearch where Meilisearch is, for {@code provider=meilisearch} ({@code MEILISEARCH_URL},
 *     {@code MEILISEARCH_API_KEY})
 */
@ConfigurationProperties("northline.search")
public record SearchProperties(
        @DefaultValue("elasticsearch") String provider,
        @DefaultValue("30s") Duration cacheTtl,
        @DefaultValue("120") int rateLimit,
        @DefaultValue({}) List<String> rateLimitExempt,
        @DefaultValue MeilisearchConnection meilisearch) {}
