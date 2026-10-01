package ca.northline.search.application;

import ca.northline.search.domain.SuggestQuery;
import java.util.List;

/** {@code GET /api/v1/search/suggest}: completions for what has been typed so far. */
public interface SuggestListings {
    List<Suggestion> suggest(SuggestQuery query);
}
