package ca.northline.worker.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * The deployed profiles (dev, staging, prod → cloud) require the variables of the search provider in use: ES_URIS for
 * Elasticsearch, MEILISEARCH_URL and MEILISEARCH_API_KEY for Meilisearch (the GCP dry run runs `dev` on Meilisearch
 * without any ES_* variable), and Elasticsearch's health indicator only when it is the engine.
 */
class SearchRequiredEnvTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "             | ES_URIS                              | true",
                "elasticsearch | ES_URIS                              | true",
                "meilisearch   | MEILISEARCH_URL, MEILISEARCH_API_KEY | false"
            })
    void theProviderDecidesWhatIsRequired(String provider, String required, String esHealth) throws IOException {
        var environment = new StandardEnvironment();
        var sources = environment.getPropertySources();
        if (provider != null) {
            sources.addFirst(new MapPropertySource("env", Map.of("SEARCH_PROVIDER", provider)));
        }
        var loader = new YamlPropertySourceLoader();
        loader.load("cloud", new ClassPathResource("application-cloud.yml")).forEach(sources::addLast);
        loader.load("base", new ClassPathResource("application.yml")).forEach(sources::addLast);

        assertThat(environment.getProperty("northline.required-env.search")).isEqualTo(required);
        assertThat(environment.getProperty("management.health.elasticsearch.enabled"))
                .isEqualTo(esHealth);
    }
}
