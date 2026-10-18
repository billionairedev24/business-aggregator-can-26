package ca.northline.worker.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.json.JsonMapper;

/**
 * The search projection (S-43): read side, document builder, the writer of the engine behind
 * {@code northline.search.provider} (Elasticsearch or Meilisearch), and the reconcile sweep.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SearchProperties.class)
public class SearchConfiguration {

    @Bean
    DocumentSource documentSource(JdbcClient jdbc) {
        return new DocumentSource(jdbc);
    }

    @Bean
    DocumentBuilder documentBuilder(JdbcClient jdbc, Clock clock, JsonMapper json) {
        return new DocumentBuilder(new CategoryTree(jdbc, clock), json);
    }

    @Bean
    SearchEngine searchEngine(SearchProperties properties, ObjectProvider<ElasticsearchClient> elasticsearch) {
        log.info(
                "Search read model: {} (listings_en / listings_fr)",
                properties.provider().equals(SearchProperties.MEILISEARCH)
                        ? "Meilisearch at " + properties.meilisearch().url()
                        : "Elasticsearch");
        return new SearchEngine(properties, elasticsearch::getObject);
    }

    @Bean
    SearchProjection searchProjection(
            JdbcClient jdbc, DocumentSource source, DocumentBuilder builder, SearchEngine engine) {
        return new SearchProjection(jdbc, source, builder, engine.writer());
    }

    @Bean
    SearchReconciler searchReconciler(
            JdbcClient jdbc,
            DocumentSource source,
            SearchProjection projection,
            TransactionOperations transactions,
            SearchProperties properties) {
        return new SearchReconciler(jdbc, source, projection, transactions, properties.reconcile());
    }
}
