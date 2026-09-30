package ca.northline.search.integration;

import ca.northline.search.application.SearchCache;
import ca.northline.search.application.SearchIndex;
import ca.northline.search.application.SearchRateLimit;
import ca.northline.search.application.SearchSettings;
import ca.northline.shared.WebhookRateLimiter;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.time.Clock;
import java.util.Arrays;
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
        return new SearchSettings(properties.cacheTtl(), properties.defaultMarket(), properties.rateLimit());
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
