package ca.northline.search.integration;

import ca.northline.search.application.SearchIndex;
import ca.northline.search.application.SearchMoment;
import ca.northline.search.application.SearchResults;
import ca.northline.search.application.Suggestion;
import ca.northline.search.domain.SearchQuery;
import ca.northline.search.domain.SuggestQuery;
import java.util.List;

/**
 * {@code northline.search.provider=local} (the {@code local} profile's default): no Elasticsearch, so nothing is found.
 * The requests are still validated. To search locally, run the compose {@code search} profile, create the indices and
 * set {@code SEARCH_PROVIDER=elasticsearch} (docs/runbooks/search.md). Refused under staging and prod.
 */
final class LocalSearchIndex implements SearchIndex {

    @Override
    public SearchResults search(SearchQuery query, SearchMoment now) {
        return SearchResults.empty();
    }

    @Override
    public List<Suggestion> suggest(SuggestQuery query) {
        return List.of();
    }
}
