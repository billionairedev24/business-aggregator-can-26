package ca.northline.search.integration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.search.*} (docs/runbooks/search.md).
 *
 * @param provider {@code elasticsearch} (listings_en / listings_fr) or {@code local} (no index; refused in staging/prod)
 * @param defaultMarket province searched when the request names none
 * @param cacheTtl how long an identical search is answered from the hot-query cache
 * @param rateLimit requests per minute and client address (anonymous callers included)
 */
@ConfigurationProperties("northline.search")
public record SearchProperties(
        @DefaultValue("elasticsearch") String provider,
        @DefaultValue("AB") String defaultMarket,
        @DefaultValue("30s") Duration cacheTtl,
        @DefaultValue("120") int rateLimit) {}
