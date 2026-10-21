package ca.northline.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

class SearchProviderImportFilterTest {

    static final String CLIENT =
            "org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchClientAutoConfiguration";
    static final String HEALTH =
            "org.springframework.boot.elasticsearch.autoconfigure.health.ElasticsearchRestHealthContributorAutoConfiguration";
    static final String DATA =
            "org.springframework.boot.data.elasticsearch.autoconfigure.DataElasticsearchAutoConfiguration";
    static final String REDIS = "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration";
    static final String[] CANDIDATES = {CLIENT, REDIS, null, HEALTH, DATA};

    static boolean[] match(MockEnvironment environment) {
        var filter = new SearchProviderImportFilter();
        filter.setEnvironment(environment);
        return filter.match(CANDIDATES, mock(AutoConfigurationMetadata.class));
    }

    @Test
    void dropsElasticsearchForOtherProviders() {
        for (var provider : new String[] {"meilisearch", "local", "Meilisearch "}) {
            assertThat(match(new MockEnvironment().withProperty(SearchProviderImportFilter.PROPERTY, provider)))
                    .as(provider)
                    .containsExactly(false, true, true, false, false);
        }
    }

    @Test
    void keepsElasticsearchWhenSelected_orWhenTheAppHasNoSearch() {
        assertThat(match(new MockEnvironment().withProperty(SearchProviderImportFilter.PROPERTY, "ELASTICSEARCH")))
                .containsExactly(true, true, true, true, true);
        assertThat(match(new MockEnvironment())).containsExactly(true, true, true, true, true);
    }

    @Test
    void resolvesThePlaceholderTheAppsUse() {
        var env = new MockEnvironment()
                .withProperty(SearchProviderImportFilter.PROPERTY, "${SEARCH_PROVIDER:elasticsearch}")
                .withProperty("SEARCH_PROVIDER", "meilisearch");
        assertThat(match(env)).containsExactly(false, true, true, false, false);
    }

    @Test
    void isRegisteredWithBootsImportFilters() {
        assertThat(SpringFactoriesLoader.forDefaultResourceLocation().load(AutoConfigurationImportFilter.class))
                .hasAtLeastOneElementOfType(SearchProviderImportFilter.class);
    }
}
