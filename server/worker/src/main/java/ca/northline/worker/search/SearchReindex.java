package ca.northline.worker.search;

import ca.northline.searchindex.IndexLayout;
import ca.northline.searchindex.ListingIndices;
import ca.northline.searchindex.SearchLanguage;
import ca.northline.worker.events.EnvelopeParser;
import ca.northline.worker.events.PoisonEventException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * A full rebuild of the search read model from Postgres (S-71), safe while everything runs:
 *
 * <ol>
 *   <li>take the reindex lock (a Postgres session advisory lock: one run at a time) and note the end of every topic
 *       the {@code search-indexer} consumes;
 *   <li>create the next versioned index per language beside the live one (no refresh, no replica while loading);
 *   <li><b>backfill</b>: every merchant, page by page, through the same {@link SearchProjection} as the live indexer
 *       (same documents, same per-merchant lock and versions);
 *   <li><b>catch up</b> from Kafka: the events published since step 1, applied to the new indices, until a pass finds
 *       nothing new;
 *   <li>restore refresh and replicas, wait for the indices, then <b>swap</b> both aliases in one atomic request —
 *       searches never see a missing or half-built index;
 *   <li>catch up once more (events the live indexer wrote to the old index while the swap happened) and refresh the
 *       merchants whose rows changed since step 1 without an event (what the reconcile sweep wrote to the old index);
 *   <li>delete the old indices (unless kept).
 * </ol>
 *
 * Anything failing before the swap deletes the new indices and leaves the live ones untouched. Writes are idempotent
 * and versioned, so an event applied twice (by the catch-up and the live indexer) is harmless.
 */
@Slf4j
public final class SearchReindex {

    static final String LOCK = "search-reindex";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    static final Duration HEALTH_TIMEOUT = Duration.ofMinutes(2);
    static final Duration SWEEP_OVERLAP = Duration.ofMinutes(2);
    static final Map<String, String> LOADING = Map.of(
            "index.refresh_interval", "-1", "index.auto_expand_replicas", "false", "index.number_of_replicas", "0");

    /** Where a run is, for the log and for tests that act between phases. */
    public enum Phase {
        LOCKED,
        INDICES_CREATED,
        BACKFILLED,
        CAUGHT_UP,
        SWAPPED,
        FINISHED
    }

    @FunctionalInterface
    public interface Listener {
        void on(Phase phase, Map<SearchLanguage, String> newIndices);
    }

    /**
     * @param keepOld leave the previous indices (for a quick way back: point the aliases at them again)
     * @param batch merchants read per page
     * @param maxPasses Kafka catch-up passes before the swap at most
     */
    public record Options(boolean keepOld, int batch, int maxPasses) {
        public static final Options DEFAULT = new Options(false, 200, 5);
    }

    public record Result(
            Map<SearchLanguage, List<String>> previous,
            Map<SearchLanguage, String> created,
            int merchants,
            int events,
            int sweptMerchants,
            boolean previousDeleted) {}

    private final DataSource dataSource;
    private final JdbcClient jdbc;
    private final TransactionOperations transactions;
    private final DocumentSource source;
    private final SearchProjection projection;
    private final ListingIndices indices;
    private final IndexLayout layout;
    private final Consumer<String, byte[]> kafka;
    private final EnvelopeParser parser;
    private final List<String> topics;
    private final Clock clock;
    private final Listener listener;

    @SuppressWarnings("ParameterNumber")
    SearchReindex(
            DataSource dataSource,
            JdbcClient jdbc,
            TransactionOperations transactions,
            DocumentSource source,
            SearchProjection projection,
            ListingIndices indices,
            IndexLayout layout,
            Consumer<String, byte[]> kafka,
            EnvelopeParser parser,
            List<String> topics,
            Clock clock,
            Listener listener) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.source = source;
        this.projection = projection;
        this.indices = indices;
        this.layout = layout;
        this.kafka = kafka;
        this.parser = parser;
        this.topics = List.copyOf(topics);
        this.clock = clock;
        this.listener = listener;
    }

    public Result run(Options options) {
        try (var lock = dataSource.getConnection()) {
            if (!tryLock(lock)) {
                throw new IllegalStateException("Another search reindex is running (Postgres advisory lock " + LOCK
                        + "); wait for it or check docs/runbooks/search.md § Reindex");
            }
            try {
                return locked(options);
            } finally {
                unlock(lock);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Search reindex: " + e.getMessage(), e);
        }
    }

    private Result locked(Options options) {
        var startedAt = jdbc.sql("select clock_timestamp()")
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
        var positions = endOffsets();
        var previous = new EnumMap<SearchLanguage, List<String>>(SearchLanguage.class);
        for (var language : SearchLanguage.values()) {
            previous.put(language, indices.aliased(language));
        }
        listener.on(Phase.LOCKED, Map.of());
        var created = createIndices(previous);
        var targets = new SearchProjection.Targets(created);
        listener.on(Phase.INDICES_CREATED, created);
        int merchants;
        int events;
        try {
            merchants = backfill(targets, options.batch());
            log.info("Search reindex: backfilled {} merchant(s) into {}", merchants, created.values());
            listener.on(Phase.BACKFILLED, created);
            events = 0;
            for (var pass = 0; pass < options.maxPasses(); pass++) {
                var applied = catchUp(positions, targets);
                events += applied;
                if (applied == 0) {
                    break;
                }
            }
            log.info("Search reindex: {} event(s) caught up from Kafka", events);
            listener.on(Phase.CAUGHT_UP, created);
            for (var language : SearchLanguage.values()) {
                var index = Objects.requireNonNull(created.get(language));
                indices.putSettings(index, served(language));
                indices.refreshAndWait(index, HEALTH_TIMEOUT);
            }
        } catch (RuntimeException e) {
            log.error(
                    "Search reindex failed before the swap; deleting {} (the live indices are untouched)",
                    created.values());
            created.values().forEach(index -> {
                try {
                    indices.delete(index);
                } catch (RuntimeException cleanup) {
                    log.warn("could not delete {}: {}", index, cleanup.toString());
                }
            });
            throw e;
        }
        indices.swap(created);
        log.info("Search reindex: aliases now point at {}", created.values());
        listener.on(Phase.SWAPPED, created);
        events += catchUp(positions, targets);
        var swept = sweep(startedAt.minus(SWEEP_OVERLAP), targets, options.batch());
        var deleted = false;
        if (!options.keepOld()) {
            previous.values().stream().flatMap(List::stream).forEach(indices::delete);
            deleted = true;
        }
        var result = new Result(Map.copyOf(previous), Map.copyOf(created), merchants, events, swept, deleted);
        log.info(
                "Search reindex done: {} merchants, {} events, {} changed merchants re-read; previous {} {}",
                merchants,
                events,
                swept,
                previous.values(),
                deleted ? "deleted" : "kept");
        listener.on(Phase.FINISHED, created);
        return result;
    }

    /**
     * Partial reindex (S-115): re-reads the given merchants, and those whose rows changed since {@code changedSince},
     * into the live aliases — no new index, no swap. For a handful of merchants after an indexer bug, a lost event or
     * a restore of part of the data. The same projection, per-merchant lock and versions as the live indexer.
     *
     * @return how many merchants were re-read
     */
    public int partial(List<String> merchantIds, Optional<Instant> changedSince, int batch) {
        var count = 0;
        for (var merchantId : merchantIds) {
            transactions.executeWithoutResult(
                    _ -> projection.refresh(new Scope.Merchant(merchantId), SearchProjection.Targets.LIVE));
            count++;
        }
        if (changedSince.isPresent()) {
            count += sweep(changedSince.get(), SearchProjection.Targets.LIVE, batch);
        }
        log.info("Search partial reindex: {} merchant(s) re-read into the live indices", count);
        return count;
    }

    /**
     * Rollback after a reindex run with {@code --keep-old} (S-115): points each alias back at the newest index older
     * than the one it serves now — both languages in one atomic request — then re-reads the merchants whose rows
     * changed since the newer index was created (what the old index missed while it was not live). The newer indices
     * are left in place for a look; delete them by hand.
     *
     * @return the indices the aliases point at now
     */
    public Map<SearchLanguage, String> rollback(int batch) {
        var to = new EnumMap<SearchLanguage, String>(SearchLanguage.class);
        var currents = new ArrayList<String>();
        for (var language : SearchLanguage.values()) {
            var current = indices.current(language)
                    .orElseThrow(() -> new IllegalStateException(
                            "The alias " + language.alias() + " doesn't point at exactly one index; fix it by hand"));
            var previous = indices.all(language).stream()
                    .filter(name -> stamp(name).compareTo(stamp(current)) < 0)
                    .max(Comparator.comparing(SearchReindex::stamp))
                    .orElseThrow(() -> new IllegalStateException("No older " + language.alias()
                            + " index to go back to: the reindex didn't keep it (run it with --keep-old next time)"
                            + " — run a full reindex instead"));
            to.put(language, previous);
            currents.add(current);
        }
        var newerCreated = currents.stream()
                .map(SearchReindex::createdAt)
                .min(Comparator.naturalOrder())
                .orElseThrow();
        indices.swap(to);
        log.info("Search rollback: aliases now point at {}", to.values());
        var swept = sweep(newerCreated.minus(SWEEP_OVERLAP), SearchProjection.Targets.LIVE, batch);
        log.info("Search rollback: {} changed merchant(s) re-read since {}", swept, newerCreated);
        return Map.copyOf(to);
    }

    /** {@code yyyyMMddHHmmss} (UTC) at the end of a versioned index name. */
    private static String stamp(String index) {
        return index.substring(index.lastIndexOf('_') + 1);
    }

    private static Instant createdAt(String index) {
        return LocalDateTime.parse(stamp(index), STAMP).toInstant(ZoneOffset.UTC);
    }

    private Map<SearchLanguage, String> createIndices(Map<SearchLanguage, List<String>> previous) {
        var created = new EnumMap<SearchLanguage, String>(SearchLanguage.class);
        var at = clock.instant();
        for (var language : SearchLanguage.values()) {
            var name = layout.newIndexName(language, at);
            var taken = new ArrayList<>(indices.all(language));
            taken.addAll(Objects.requireNonNull(previous.get(language)));
            var bump = at;
            while (taken.contains(name)) {
                bump = bump.plusSeconds(1);
                name = layout.newIndexName(language, bump);
            }
            indices.create(name, layout.definition(language), LOADING, false);
            created.put(language, name);
        }
        return Map.copyOf(created);
    }

    /** The settings the layout serves with (what {@link #LOADING} turned off). */
    private Map<String, String> served(SearchLanguage language) {
        var settings = layout.definition(language).settings();
        return Map.of(
                "index.refresh_interval", settings.path("refresh_interval").asString("1s"),
                "index.auto_expand_replicas",
                        settings.path("auto_expand_replicas").asString("0-1"));
    }

    private int backfill(SearchProjection.Targets targets, int batch) {
        var count = 0;
        var after = "";
        while (true) {
            var page = source.merchantIds(after, batch);
            for (var merchantId : page) {
                transactions.executeWithoutResult(_ -> projection.backfill(merchantId, targets));
                count++;
            }
            if (page.size() < batch) {
                return count;
            }
            after = page.getLast();
        }
    }

    /** Applies every record between {@code positions} and the topics' current end; moves {@code positions} there. */
    private int catchUp(Map<TopicPartition, Long> positions, SearchProjection.Targets targets) {
        var end = kafka.endOffsets(positions.keySet());
        var pending = new HashMap<TopicPartition, Long>();
        positions.forEach((tp, from) -> {
            if (end.getOrDefault(tp, from) > from) {
                pending.put(tp, from);
            }
        });
        if (pending.isEmpty()) {
            return 0;
        }
        kafka.assign(pending.keySet());
        pending.forEach(kafka::seek);
        var applied = 0;
        while (pending.keySet().stream().anyMatch(tp -> kafka.position(tp) < end.getOrDefault(tp, 0L))) {
            for (var record : kafka.poll(Duration.ofMillis(500))) {
                var tp = new TopicPartition(record.topic(), record.partition());
                if (record.offset() >= end.getOrDefault(tp, 0L)) {
                    continue;
                }
                try {
                    var scope = Scope.of(parser.parse(record));
                    transactions.executeWithoutResult(_ -> projection.refresh(scope, targets));
                    applied++;
                } catch (PoisonEventException e) {
                    log.warn("Search reindex: skipping {}", e.getMessage());
                }
            }
        }
        positions.putAll(end);
        kafka.unsubscribe();
        return applied;
    }

    private int sweep(Instant since, SearchProjection.Targets targets, int batch) {
        var count = 0;
        var from = since;
        while (true) {
            var changed = source.changedSince(from, batch);
            for (var merchant : changed) {
                transactions.executeWithoutResult(
                        _ -> projection.refresh(new Scope.Merchant(merchant.merchantId()), targets));
                count++;
            }
            if (changed.size() < batch) {
                return count;
            }
            from = changed.getLast().changedAt();
        }
    }

    private Map<TopicPartition, Long> endOffsets() {
        var partitions = new ArrayList<TopicPartition>();
        for (var topic : topics) {
            kafka.partitionsFor(topic, Duration.ofSeconds(30))
                    .forEach(p -> partitions.add(new TopicPartition(topic, p.partition())));
        }
        return new HashMap<>(kafka.endOffsets(partitions));
    }

    private static boolean tryLock(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("select pg_try_advisory_lock(hashtextextended(?, 0))")) {
            statement.setString(1, LOCK);
            try (var rs = statement.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static void unlock(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("select pg_advisory_unlock(hashtextextended(?, 0))")) {
            statement.setString(1, LOCK);
            statement.execute();
        }
    }
}
