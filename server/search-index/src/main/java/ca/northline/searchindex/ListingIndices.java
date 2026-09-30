package ca.northline.searchindex;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.synonyms.SynonymRule;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import tools.jackson.databind.json.JsonMapper;

/**
 * The index and synonym-set operations of the listings read model, on the official Elasticsearch 9 Java client: which
 * concrete index an alias serves, an index's {@code _meta}, creating an index from a {@link IndexLayout.Definition},
 * adding mappings in place, and reading and replacing a synonym set. Used by the bootstrap (S-42) and the reindex
 * (S-71); the indexer (S-43) and the search API (S-44) only ever address the aliases.
 */
public final class ListingIndices {

    /** Elasticsearch's page size limit for {@code GET _synonyms/<set>}. */
    private static final int MAX_RULES = 10_000;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ElasticsearchClient es;

    public ListingIndices(ElasticsearchClient es) {
        this.es = es;
    }

    /** The concrete indices the alias of {@code language} points at (one, unless someone edited aliases by hand). */
    public List<String> aliased(SearchLanguage language) {
        return call(() -> {
            if (!es.indices().existsAlias(a -> a.name(language.alias())).value()) {
                return List.of();
            }
            return es.indices().getAlias(a -> a.name(language.alias())).aliases().keySet().stream()
                    .sorted()
                    .toList();
        });
    }

    /** The single index behind the alias, if there is exactly one. */
    public Optional<String> current(SearchLanguage language) {
        var indices = aliased(language);
        return indices.size() == 1 ? Optional.of(indices.getFirst()) : Optional.empty();
    }

    /** Every concrete listings index of the language, aliased or not (a reindex leaves none behind unless it failed). */
    public List<String> all(SearchLanguage language) {
        return call(() ->
                es
                        .indices()
                        .get(g -> g.index(language.alias() + "_v*").allowNoIndices(true))
                        .indices()
                        .keySet()
                        .stream()
                        .filter(name -> IndexLayout.isIndexOf(language, name))
                        .sorted()
                        .toList());
    }

    /** {@code _meta.northline} of an index; empty when the index wasn't made from the layout. */
    public Optional<IndexLayout.IndexMeta> meta(String index) {
        return call(() -> {
            var record = es.indices().getMapping(g -> g.index(index)).get(index);
            if (record == null || record.mappings().meta().get("northline") == null) {
                return Optional.empty();
            }
            return IndexLayout.IndexMeta.parse(
                    record.mappings().meta().get("northline").toJson().toString());
        });
    }

    /** Creates {@code index} from the definition; with {@code withAlias} it becomes the alias' write index at once. */
    public void create(
            String index, IndexLayout.Definition definition, Map<String, String> overrides, boolean withAlias) {
        var body = definition.createBody(overrides, withAlias);
        call(() -> es.indices().create(c -> c.index(index).withJson(new StringReader(body))));
    }

    /**
     * Adds the definition's mappings (and its {@code _meta}) to an existing index. Elasticsearch accepts only additive
     * changes (new fields, new sub-fields); anything else is an {@link ElasticsearchException} with status 400.
     */
    public void putMappings(String index, IndexLayout.Definition definition) {
        var body = JSON.writeValueAsString(definition.mappingsWithMeta());
        call(() -> es.indices().putMapping(p -> p.index(index).withJson(new StringReader(body))));
    }

    /** The rules of a synonym set in Solr format; empty when the set doesn't exist. */
    public Optional<List<String>> synonyms(String set) {
        try {
            var rules = es.synonyms().getSynonym(g -> g.id(set).size(MAX_RULES)).synonymsSet().stream()
                    .map(r -> r.synonyms())
                    .toList();
            return Optional.of(rules);
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Replaces a synonym set (creates it when missing). Elasticsearch reloads every search analyzer that uses the set:
     * the change is live at once, without a reindex.
     */
    public void putSynonyms(String set, List<String> rules) {
        if (rules.size() > MAX_RULES) {
            throw new IllegalArgumentException(set + ": more than " + MAX_RULES + " rules");
        }
        var list = new ArrayList<SynonymRule>();
        IntStream.range(0, rules.size())
                .forEach(i -> list.add(SynonymRule.of(r -> r.id("r" + i).synonyms(rules.get(i)))));
        call(() -> es.synonyms().putSynonym(p -> p.id(set).synonymsSet(list)));
    }

    @FunctionalInterface
    interface Call<T> {
        T call() throws IOException;
    }

    private static <T> T call(Call<T> call) {
        try {
            return call.call();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
