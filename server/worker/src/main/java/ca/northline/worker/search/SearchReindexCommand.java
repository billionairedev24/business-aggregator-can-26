package ca.northline.worker.search;

import ca.northline.searchindex.IndexLayout;
import ca.northline.searchindex.ListingIndices;
import ca.northline.searchindex.SearchLanguage;
import ca.northline.worker.events.EnvelopeParser;
import ca.northline.worker.events.EventSchemas;
import ca.northline.worker.topics.TopicCatalogue;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.json.JsonMapper;

/**
 * The full reindex (S-71; docs/runbooks/search.md § Reindex), without starting the worker's consumers — a one-off Job
 * of the worker image, or by hand:
 *
 * <pre>
 * ./gradlew :worker:searchReindex                      # build new indices from Postgres, catch up, swap, delete the old
 * ./gradlew :worker:searchReindex --args='--keep-old'  # keep the previous indices (for --rollback)
 * ./gradlew :worker:searchReindex --args='--partial --merchant=01J…,01J… [--since=2026-10-02T08:00:00Z]'
 * ./gradlew :worker:searchReindex --args='--rollback'  # aliases back to the indices --keep-old kept
 * java -cp @/app/jib-classpath-file ca.northline.worker.search.SearchReindexCommand [--keep-old] [--batch=200]
 * </pre>
 *
 * It uses the worker's database, Kafka and Elasticsearch settings ({@code DB_*}, {@code KAFKA_*}, {@code ES_*}). Exit
 * codes: 0 done, 1 failed (before the swap nothing changed for searches), 2 another reindex is running.
 */
@Slf4j
public final class SearchReindexCommand {

    private SearchReindexCommand() {}

    public static void main(String[] args) {
        int status;
        try {
            var list = Arrays.asList(args);
            if (list.contains("--partial")) {
                partial(args);
            } else if (list.contains("--rollback")) {
                rollback(args);
            } else {
                run(args);
            }
            status = 0;
        } catch (IllegalStateException e) {
            log.error("Search reindex: {}", e.getMessage(), e);
            status = e.getMessage() != null && e.getMessage().startsWith("Another search reindex") ? 2 : 1;
        } catch (RuntimeException e) {
            log.error("Search reindex: {}", e.getMessage(), e);
            status = 1;
        }
        System.exit(status);
    }

    public static SearchReindex.Result run(String... args) {
        return withReindex(args, SearchReindex::run);
    }

    /** {@code --partial}: re-read merchants into the live aliases; returns how many. */
    public static int partial(String... args) {
        var merchants = option(args, "merchant").map(v -> List.of(v.split(","))).orElse(List.of());
        var since = option(args, "since").map(Instant::parse);
        if (merchants.isEmpty() && since.isEmpty()) {
            throw new IllegalArgumentException("--partial needs --merchant=<id>[,<id>…] and/or --since=<instant>");
        }
        var batch = option(args, "batch").map(Integer::parseInt).orElse(SearchReindex.Options.DEFAULT.batch());
        return withReindex(args, (reindex, _) -> reindex.partial(merchants, since, batch));
    }

    /** {@code --rollback}: the aliases back to the indices a {@code --keep-old} run left. */
    public static Map<SearchLanguage, String> rollback(String... args) {
        var batch = option(args, "batch").map(Integer::parseInt).orElse(SearchReindex.Options.DEFAULT.batch());
        return withReindex(args, (reindex, _) -> reindex.rollback(batch));
    }

    private static <T> T withReindex(String[] args, BiFunction<SearchReindex, SearchReindex.Options, T> what) {
        var options = new SearchReindex.Options(
                Arrays.asList(args).contains("--keep-old"),
                option(args, "batch").map(Integer::parseInt).orElse(SearchReindex.Options.DEFAULT.batch()),
                SearchReindex.Options.DEFAULT.maxPasses());
        try (var context = new SpringApplicationBuilder(CommandContext.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .run(args)) {
            var json = context.getBean(JsonMapper.class);
            var jdbc = context.getBean(JdbcClient.class);
            var es = context.getBean(ElasticsearchClient.class);
            var clock = Clock.systemUTC();
            var consumerProps =
                    new HashMap<>(context.getBean(KafkaProperties.class).buildConsumerProperties());
            consumerProps.remove(ConsumerConfig.GROUP_ID_CONFIG);
            consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            try (var kafka =
                    new KafkaConsumer<>(consumerProps, new StringDeserializer(), new ByteArrayDeserializer())) {
                var source = new DocumentSource(jdbc);
                var projection = new SearchProjection(
                        jdbc, source, new DocumentBuilder(new CategoryTree(jdbc, clock), json), es);
                var reindex = new SearchReindex(
                        context.getBean(DataSource.class),
                        jdbc,
                        context.getBean(TransactionOperations.class),
                        source,
                        projection,
                        new ListingIndices(es),
                        IndexLayout.fromClasspath(),
                        kafka,
                        new EnvelopeParser(json, EventSchemas.fromClasspath(json)),
                        SearchIndexer.topics(TopicCatalogue.fromClasspath()),
                        clock,
                        (phase, indices) -> log.info("Search reindex: {} {}", phase, indices.values()));
                return what.apply(reindex, options);
            }
        }
    }

    private static Optional<String> option(String[] args, String name) {
        return Arrays.stream(args)
                .filter(a -> a.startsWith("--" + name + "="))
                .map(a -> a.substring(name.length() + 3))
                .filter(v -> !v.isBlank())
                .findFirst();
    }

    /** Kafka, the database, Elasticsearch and JSON only: no listeners, web server or Valkey. */
    @ImportAutoConfiguration({
        JacksonAutoConfiguration.class,
        KafkaAutoConfiguration.class,
        DataSourceAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        JdbcClientAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        TransactionAutoConfiguration.class,
        ElasticsearchRestClientAutoConfiguration.class,
        ElasticsearchClientAutoConfiguration.class
    })
    static class CommandContext {}
}
