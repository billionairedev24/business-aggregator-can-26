package ca.northline.worker.search;

import ca.northline.searchindex.SearchLanguage;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link ListingWriter} on Elasticsearch: both languages in one bulk request, every write with
 * {@code version_type=external_gte} and the projection's version, so an older snapshot never overwrites a newer one
 * (a 409 on an item = someone already wrote something newer; counted as stale). Deletes carry the version too, so a
 * stale write can't resurrect a deleted document within {@code index.gc_deletes}.
 */
final class ElasticsearchListingWriter implements ListingWriter {

    private final ElasticsearchClient es;

    ElasticsearchListingWriter(ElasticsearchClient es) {
        this.es = es;
    }

    @Override
    public SearchProjection.Outcome write(
            SearchProjection.Targets targets, DocumentBuilder.Built built, List<String> deletes, long version) {
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
            return new SearchProjection.Outcome(version, 0, 0, 0);
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
            return new SearchProjection.Outcome(version, indexed, deleted, stale);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Both languages are written in one bulk request with the same ids, so one index answers for both. The refresh
     * makes documents written in the last second searchable (S-137); it costs a new segment only when something was
     * written since the last one.
     */
    @Override
    public List<String> indexedIds(String index, String merchantId) {
        try {
            es.indices().refresh(r -> r.index(index));
            return es
                    .search(
                            s -> s.index(index)
                                    .query(q ->
                                            q.term(t -> t.field("merchantId").value(merchantId)))
                                    .source(src -> src.fetch(false))
                                    .size(SearchProjection.MAX_DOCUMENTS_PER_MERCHANT),
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
