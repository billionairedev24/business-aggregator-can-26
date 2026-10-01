package ca.northline.worker.search;

import ca.northline.searchindex.ListingDocument;
import ca.northline.searchindex.SearchLanguage;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Brings the documents of one {@link Scope} in line with Postgres (S-43): re-reads the scope, then writes both languages
 * in one bulk request — visible documents indexed, everything else of the scope deleted. Events only say <em>what</em>
 * to look at; the document is always built from the current rows, so a redelivered, replayed or out-of-order event
 * does no harm.
 *
 * <p><b>Versions.</b> Every write carries {@code version_type=external_gte} with a version taken from Postgres'
 * {@code clock_timestamp()} (µs) <em>after</em> a transaction-scoped advisory lock on the merchant: refreshes of one
 * merchant (the indexer's replicas, the reconciler, the reindex) run one after the other and each reads what the
 * previous committed, so a later version is never older data, and Elasticsearch refuses to let an older snapshot
 * overwrite a newer one (a 409 on an item = someone already wrote something newer; that's fine). Deletes carry the
 * version too, so a stale write can't resurrect a deleted document within {@code index.gc_deletes}.
 *
 * <p><b>Rows deleted from Postgres</b> are found in the index: a whole-merchant refresh searches for the merchant's
 * ids. A search only sees what Elasticsearch has refreshed (every second), so the index is refreshed first (S-137):
 * a dish indexed a moment before its deletion is removed on the first event, not at the next reconcile sweep.
 *
 * <p>Must run inside a transaction (the lock is held until it ends): the indexer's comes from {@code EventProcessing},
 * the reconciler and the reindex open their own.
 */
@Slf4j
public class SearchProjection {

    /** Documents a merchant may have at most (the id lookup of a whole-merchant refresh reads this many). */
    static final int MAX_DOCUMENTS_PER_MERCHANT = 10_000;

    private final JdbcClient jdbc;
    private final DocumentSource source;
    private final DocumentBuilder builder;
    private final ElasticsearchClient es;

    SearchProjection(JdbcClient jdbc, DocumentSource source, DocumentBuilder builder, ElasticsearchClient es) {
        this.jdbc = jdbc;
        this.source = source;
        this.builder = builder;
        this.es = es;
    }

    /** Where to write: the live aliases, or the new indices of a reindex. */
    public record Targets(Map<SearchLanguage, String> indices) {
        public static final Targets LIVE = new Targets(
                Map.of(SearchLanguage.EN, SearchLanguage.EN.alias(), SearchLanguage.FR, SearchLanguage.FR.alias()));

        String of(SearchLanguage language) {
            return Objects.requireNonNull(indices.get(language), () -> "no index for " + language);
        }
    }

    /** What one refresh did, counted per write (a document is written once per language). */
    public record Outcome(long version, int indexed, int deleted, int stale) {}

    /** Re-reads {@code scope} and writes it to {@code targets}. */
    public Outcome refresh(Scope scope, Targets targets) {
        return refresh(scope, targets, true);
    }

    /**
     * The reindex backfill's first write of a merchant to indices created by that run (S-71): they hold nothing of the
     * merchant yet, so there is nothing stale to look up, and no refresh per merchant on indices that load with
     * refresh off.
     */
    public Outcome backfill(String merchantId, Targets targets) {
        return refresh(new Scope.Merchant(merchantId), targets, false);
    }

    private Outcome refresh(Scope scope, Targets targets, boolean findStale) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("SearchProjection.refresh needs a transaction (the merchant lock)");
        }
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "search:" + scope.merchantId())
                .query((rs, _) -> 1)
                .list();
        var version = jdbc.sql("select (extract(epoch from clock_timestamp()) * 1000000)::bigint")
                .query(Long.class)
                .single();
        var built = switch (scope) {
            case Scope.Listing(var kind, var id, var merchantId) ->
                builder.build(
                        kind.equals(ListingDocument.SERVICE)
                                ? source.service(merchantId, id)
                                : source.offer(merchantId, id),
                        false);
            case Scope.Dish(var id, var merchantId) -> builder.build(source.dish(merchantId, id), false);
            case Scope.Merchant(var merchantId) -> builder.build(source.merchant(merchantId), true);
        };
        var deletes = new LinkedHashSet<>(built.hidden());
        switch (scope) {
            case Scope.Listing listing when built.visibleIds().isEmpty() -> deletes.add(listing.id());
            case Scope.Dish dish when built.visibleIds().isEmpty() -> deletes.add(dish.id());
            // documents of rows deleted from Postgres are found in the index itself
            case Scope.Merchant merchant -> {
                deletes.add(merchant.merchantId());
                if (findStale) {
                    deletes.addAll(indexedIds(targets.of(SearchLanguage.EN), merchant.merchantId()));
                }
                built.visibleIds().forEach(deletes::remove);
            }
            default -> {}
        }
        return write(targets, built, List.copyOf(deletes), version);
    }

    private Outcome write(Targets targets, DocumentBuilder.Built built, List<String> deletes, long version) {
        var operations = new ArrayList<BulkOperation>();
        for (var language : SearchLanguage.values()) {
            var index = targets.of(language);
            for (var doc : built.of(language)) {
                operations.add(BulkOperation.of(b -> b.index(i -> i.index(index)
                        .id(doc.id())
                        .version(version)
                        .versionType(VersionType.ExternalGte)
                        .document(doc))));
            }
            for (var id : deletes) {
                operations.add(BulkOperation.of(b ->
                        b.delete(d -> d.index(index).id(id).version(version).versionType(VersionType.ExternalGte))));
            }
        }
        if (operations.isEmpty()) {
            return new Outcome(version, 0, 0, 0);
        }
        try {
            var response = es.bulk(b -> b.operations(operations));
            var failures = new ArrayList<String>();
            int indexed = 0;
            int deleted = 0;
            int stale = 0;
            for (var item : response.items()) {
                var result = Objects.requireNonNullElse(item.result(), "");
                if (item.status() == 409) {
                    stale++; // a newer version is already there
                } else if (item.error() != null && item.status() != 404) {
                    failures.add(item.id() + " → " + item.index() + ": "
                            + item.error().reason());
                } else if (result.equals("created") || result.equals("updated")) {
                    indexed++;
                } else if (result.equals("deleted")) {
                    deleted++;
                }
            }
            if (!failures.isEmpty()) {
                throw new IllegalStateException("Elasticsearch refused " + failures.size() + " write(s): "
                        + String.join("; ", failures.subList(0, Math.min(5, failures.size()))));
            }
            return new Outcome(version, indexed, deleted, stale);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Ids the index holds for a merchant (its own document included). Both languages are written in one bulk request
     * with the same ids, so one index answers for both. The refresh makes documents written in the last second
     * searchable; it costs a new segment only when something was written since the last one.
     */
    private List<String> indexedIds(String index, String merchantId) {
        try {
            es.indices().refresh(r -> r.index(index));
            return es
                    .search(
                            s -> s.index(index)
                                    .query(q ->
                                            q.term(t -> t.field("merchantId").value(merchantId)))
                                    .source(src -> src.fetch(false))
                                    .size(MAX_DOCUMENTS_PER_MERCHANT),
                            Void.class)
                    .hits()
                    .hits()
                    .stream()
                    .map(h -> h.id())
                    .filter(Objects::nonNull)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
