package ca.northline.platform;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * S-163: Spring Boot's Elasticsearch auto-configuration (the client, its HTTP connection pool, its health check) only
 * when {@code northline.search.provider} is {@code elasticsearch}. With Meilisearch (the dry run) or the {@code local}
 * index the api and the worker never call Elasticsearch, so neither its classes nor its threads are loaded. An app
 * that doesn't set the property (auth, bff) is left alone.
 *
 * <p>Registered in {@code META-INF/spring.factories}; runs with Boot's own class and bean conditions, before any
 * auto-configuration class is read.
 */
public final class SearchProviderImportFilter implements AutoConfigurationImportFilter, EnvironmentAware {

    static final String PROPERTY = "northline.search.provider";
    static final String ELASTICSEARCH = "elasticsearch";
    private static final List<String> PACKAGES =
            List.of("org.springframework.boot.elasticsearch.", "org.springframework.boot.data.elasticsearch.");

    private @Nullable Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public boolean[] match(@Nullable String[] candidates, AutoConfigurationMetadata metadata) {
        var keep = new boolean[candidates.length];
        var elasticsearch = usesElasticsearch();
        for (var i = 0; i < candidates.length; i++) {
            var candidate = candidates[i];
            keep[i] = elasticsearch || candidate == null || PACKAGES.stream().noneMatch(candidate::startsWith);
        }
        return keep;
    }

    private boolean usesElasticsearch() {
        var provider = environment == null ? null : environment.getProperty(PROPERTY);
        return provider == null || provider.strip().toLowerCase(Locale.ROOT).equals(ELASTICSEARCH);
    }
}
