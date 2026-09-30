package ca.northline.worker.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.json.JsonMapper;

/** The search projection (S-43): read side, document builder, writer, and the reconcile sweep. */
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
    SearchProjection searchProjection(
            JdbcClient jdbc, DocumentSource source, DocumentBuilder builder, ElasticsearchClient es) {
        return new SearchProjection(jdbc, source, builder, es);
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
