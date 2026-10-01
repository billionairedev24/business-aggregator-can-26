package ca.northline.search.application;

import ca.northline.search.domain.SearchQuery;

/** {@code GET /api/v1/search}: a page of results with facets. */
public interface SearchListings {
    SearchResults search(SearchQuery query);
}
