package ca.northline.search.application;

import ca.northline.search.domain.SearchQuery;
import ca.northline.search.domain.SuggestQuery;
import java.util.List;

/**
 * Outbound port: the read model. {@code elasticsearch} = listings_en / listings_fr (S-42/S-43); {@code local} = no
 * index (empty results) for the {@code local} profile, which runs without Elasticsearch.
 */
public interface SearchIndex {

    SearchResults search(SearchQuery query, SearchMoment now);

    List<Suggestion> suggest(SuggestQuery query);
}
