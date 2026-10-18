package ca.northline.search.integration;

import ca.northline.search.application.SearchMoment;
import ca.northline.search.application.SearchResults.Hit;
import ca.northline.search.domain.SearchQuery;
import ca.northline.searchindex.ListingDocument;

/** What is true of a result now, computed from its document the same way whichever engine found it. */
final class SearchHits {

    private SearchHits() {}

    static Hit hit(ListingDocument doc, SearchQuery q, SearchMoment now) {
        var near = q.near();
        var location = doc.location();
        Double distance = near == null || location == null
                ? null
                : Math.round(near.kmTo(location.lat(), location.lon()) * 10) / 10.0;
        var soldOut = now.today().equals(doc.soldOutOn());
        var paused = doc.pausedUntil() != null && doc.pausedUntil().isAfter(now.instant());
        var open = !soldOut
                && !paused
                && doc.openHours().stream().anyMatch(r -> r.gte() <= now.minuteOfWeek() && now.minuteOfWeek() < r.lt());
        var cutoff = doc.deliveryCutoffMinute();
        var tonight = doc.fulfilment().contains("pooled")
                && cutoff != null
                && now.minuteOfDay() < cutoff
                && Boolean.TRUE.equals(doc.inStock());
        return new Hit(doc, distance, open, soldOut, tonight);
    }
}
