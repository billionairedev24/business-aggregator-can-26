package ca.northline.worker.search;

import ca.northline.searchindex.IndexBootstrap.Finding;
import ca.northline.searchindex.IndexBootstrap.Mode;
import ca.northline.searchindex.IndexBootstrap.Report;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.util.Arrays;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;

/**
 * The search-indices deploy step (S-42), without starting the worker's consumers: a Helm pre-install/pre-upgrade hook
 * Job after the Kafka topics Job, or by hand.
 *
 * <pre>
 * ./gradlew :worker:searchIndices --args='plan'     # what apply would do (exit 0)
 * ./gradlew :worker:searchIndices --args='apply'    # synonym sets, missing aliases/indices, new fields in place
 * ./gradlew :worker:searchIndices --args='verify'   # change nothing; exit 3 when anything is missing or drifted
 * java -cp @/app/jib-classpath-file ca.northline.worker.search.SearchIndicesCommand apply     # in the worker image
 * </pre>
 *
 * Connection settings are the worker's own: the engine of {@code SEARCH_PROVIDER}, then {@code spring.elasticsearch.*}
 * ← {@code ES_URIS}, {@code ES_USERNAME}, {@code ES_PASSWORD}, or {@code northline.search.meilisearch.*} ←
 * {@code MEILISEARCH_URL}, {@code MEILISEARCH_API_KEY} (Meilisearch: {@code MeilisearchBootstrap}). A change that needs a reindex is reported ({@code REINDEX REQUIRED}, exit 3 under
 * {@code verify}) and left to the reindex Job (S-71, docs/runbooks/search.md). Exit codes: 0 done, 3 drift left
 * ({@code verify}), 1 error.
 */
@Slf4j
public final class SearchIndicesCommand {

    public static final int DRIFT = 3;

    private SearchIndicesCommand() {}

    public static void main(String[] args) {
        int status;
        try {
            status = run(args);
        } catch (RuntimeException e) {
            log.error("Search indices: {}", e.getMessage(), e);
            status = 1;
        }
        System.exit(status);
    }

    /** Runs {@code plan}, {@code verify} or {@code apply}; returns the exit status. */
    public static int run(String... args) {
        var mode = mode(args);
        try (var context = new SpringApplicationBuilder(CommandContext.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .run(args)) {
            var engine = new SearchEngine(
                    context.getBean(SearchProperties.class), () -> context.getBean(ElasticsearchClient.class));
            log.info("Search indices on {}", engine.provider());
            var report = engine.bootstrap(mode);
            log(report);
            return mode == Mode.VERIFY && report.hasDrift() ? DRIFT : 0;
        }
    }

    private static Mode mode(String... args) {
        var words = Arrays.stream(args).filter(a -> !a.startsWith("--")).toList();
        var word = words.isEmpty() ? "plan" : words.getFirst();
        return switch (word) {
            case "plan", "verify", "apply" -> Mode.valueOf(word.toUpperCase(Locale.ROOT));
            default -> throw new IllegalArgumentException("Unknown command " + word + ": plan | verify | apply");
        };
    }

    static void log(Report report) {
        for (var finding : report.findings()) {
            switch (finding) {
                case Finding.InSync(var subject) -> log.info("ok        {}", subject);
                case Finding.SynonymsMissing(var set, var rules) -> log.warn("MISSING   {} ({} rules)", set, rules);
                case Finding.SynonymsCreated(var set, var rules) -> log.info("CREATED   {} ({} rules)", set, rules);
                case Finding.SynonymsChanged(var set, var added, var removed) ->
                    log.warn("CHANGED   {} (+{} −{} rules)", set, added, removed);
                case Finding.SynonymsUpdated(var set, var added, var removed) ->
                    log.info("UPDATED   {} (+{} −{} rules; search analyzers reloaded)", set, added, removed);
                case Finding.IndexMissing(var alias) -> log.warn("MISSING   {}", alias);
                case Finding.IndexCreated(var alias, var index) -> log.info("CREATED   {} → {}", alias, index);
                case Finding.MappingsChanged(var alias, var index) ->
                    log.warn("CHANGED   {} → {}: new mappings or settings to apply in place", alias, index);
                case Finding.MappingsUpdated(var alias, var index) ->
                    log.info("UPDATED   {} → {}: new mappings or settings applied in place", alias, index);
                case Finding.ReindexRequired(var alias, var index, var reason) ->
                    log.warn(
                            "REINDEX REQUIRED {} → {}: {} — run the reindex Job (docs/runbooks/search.md § Reindex)",
                            alias,
                            index,
                            reason);
            }
        }
        log.info("Search indices {}", report.summary());
    }

    /** The search engine's client only: no listeners, database, Kafka or Valkey. */
    @ImportAutoConfiguration({
        JacksonAutoConfiguration.class,
        ElasticsearchRestClientAutoConfiguration.class,
        ElasticsearchClientAutoConfiguration.class
    })
    @EnableConfigurationProperties(SearchProperties.class)
    static class CommandContext {}
}
