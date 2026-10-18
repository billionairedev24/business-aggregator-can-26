package ca.northline.worker.search;

import ca.northline.searchindex.MeilisearchClient;
import ca.northline.searchindex.MeilisearchDocuments;
import ca.northline.searchindex.SearchLanguage;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * {@link ListingWriter} on Meilisearch: per language one "add or replace documents" task and one "delete documents"
 * task, then the writer waits until Meilisearch has applied them (writes are asynchronous there), so a failure fails
 * the refresh — and the event is retried, then dead-lettered — like a refused Elasticsearch bulk item, and the next
 * refresh of the merchant reads what this one wrote.
 *
 * <p><b>No versions.</b> Meilisearch has no conditional writes ({@code external_gte}). Its order guarantee replaces
 * them: tasks of one index are applied in the order they were enqueued, and every refresh of a merchant enqueues and
 * waits <em>under the merchant's advisory lock</em>, so a later refresh (newer rows) is always applied after an
 * earlier one. {@code stale} is therefore always 0. A write that timed out waiting may still be applied later; the
 * retried refresh enqueues after it and wins.
 */
final class MeilisearchListingWriter implements ListingWriter {

    private final MeilisearchClient meili;

    MeilisearchListingWriter(MeilisearchClient meili) {
        this.meili = meili;
    }

    @Override
    public SearchProjection.Outcome write(
            SearchProjection.Targets targets, DocumentBuilder.Built built, List<String> deletes, long version) {
        var tasks = new ArrayList<Long>();
        for (var language : SearchLanguage.values()) {
            var index = targets.of(language);
            var documents = JsonNodeFactory.instance.arrayNode();
            built.of(language).forEach(doc -> documents.add(MeilisearchDocuments.toMeilisearch(doc)));
            if (!documents.isEmpty()) {
                tasks.add(meili.addDocuments(index, documents));
            }
            if (!deletes.isEmpty()) {
                tasks.add(meili.deleteDocuments(index, deletes));
            }
        }
        var indexed = 0;
        var deleted = 0;
        for (var task : meili.await(tasks)) {
            indexed += task.path("details").path("indexedDocuments").asInt(0);
            deleted += task.path("details").path("deletedDocuments").asInt(0);
        }
        return new SearchProjection.Outcome(version, indexed, deleted, 0);
    }

    /** Every write was awaited, so the index already shows it (no refresh to ask for). */
    @Override
    public List<String> indexedIds(String index, String merchantId) {
        return meili.ids(
                index,
                "merchantId = " + MeilisearchDocuments.quote(merchantId),
                SearchProjection.MAX_DOCUMENTS_PER_MERCHANT);
    }
}
