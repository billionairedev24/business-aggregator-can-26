package ca.northline.search.integration;

import ca.northline.search.application.SearchCache;
import ca.northline.search.application.SearchIndex;
import ca.northline.search.application.SearchRateLimit;
import ca.northline.search.application.SearchSettings;
import ca.northline.search.domain.SearchQuery;
import ca.northline.shared.WebhookRateLimiter;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wiring of the search API (S-44): the index behind {@code northline.search.provider} ({@code elasticsearch} |
 * {@code local}; {@code local} refused under staging and prod), the hot-query cache (Redis outside local/test), the
 * per-address rate limit.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SearchProperties.class)
class SearchConfig {

    @Bean
    SearchSettings searchSettings(SearchProperties properties) {
        return new SearchSettings(
                properties.cacheTtl(),
                properties.defaultMarket().isBlank()
                        ? null
                        : properties.defaultMarket().strip().toUpperCase(Locale.ROOT),
                properties.rateLimit(),
                markets(properties.markets()));
    }

    /** {@code AB=America/Edmonton,BC=America/Vancouver} → code → zone; a malformed entry stops the api at start. */
    static Map<String, ZoneId> markets(String spec) {
        var markets = new LinkedHashMap<String, ZoneId>();
        for (var entry : spec.split(",")) {
            if (entry.isBlank()) {
                continue;
            }
            var pair = entry.split("=", 2);
            var code = pair[0].strip().toUpperCase(Locale.ROOT);
            if (pair.length != 2 || !SearchQuery.MARKET.matcher(code).matches()) {
                throw new IllegalStateException(
                        "SEARCH_MARKETS entries are CODE=Time/Zone (a two-letter province or territory code), not: "
                                + entry.strip());
            }
            try {
                markets.put(code, ZoneId.of(pair[1].strip()));
            } catch (DateTimeException e) {
                throw new IllegalStateException("SEARCH_MARKETS: " + code + " has no valid time zone: " + pair[1], e);
            }
        }
        return markets;
    }

    @Bean
    SearchIndex searchIndex(
            SearchProperties properties,
            Environment environment,
            ObjectProvider<ElasticsearchClient> es,
            JsonMapper json) {
        return switch (properties.provider()) {
            case "elasticsearch" -> {
                log.info("Search: Elasticsearch (listings_en / listings_fr)");
                yield new ElasticsearchSearchIndex(es.getObject(), json);
            }
            case "local" -> {
                if (Arrays.stream(environment.getActiveProfiles()).anyMatch(Set.of("staging", "prod")::contains)) {
                    throw new IllegalStateException(
                            "SEARCH_PROVIDER=local is refused under staging and prod: set elasticsearch");
                }
                log.info("Search: local (no index — every search is empty; docs/runbooks/search.md)");
                yield new LocalSearchIndex();
            }
            default ->
                throw new IllegalStateException(
                        "northline.search.provider must be elasticsearch or local, not " + properties.provider());
        };
    }

    @Bean
    @Profile("!local & !test")
    SearchCache redisSearchCache(StringRedisTemplate redis) {
        return new RedisSearchCache(redis);
    }

    @Bean
    @Profile("local | test")
    SearchCache memorySearchCache(Clock clock) {
        return new MemorySearchCache(clock);
    }

    /** Fixed one-minute window per client address, per api instance. */
    @Bean
    SearchRateLimit searchRateLimit(SearchProperties properties, Clock clock) {
        return new WebhookRateLimiter(properties.rateLimit(), clock)::allow;
    }
}
