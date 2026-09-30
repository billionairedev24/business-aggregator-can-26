package ca.northline.worker.search;

import ca.northline.searchindex.ListingDocument;
import ca.northline.worker.events.EventEnvelope;
import java.util.Objects;

/**
 * What an event makes the projection re-read from Postgres: one listing or dish, or everything of a merchant (its
 * status, name, page, hours, rating or pause touch every document it has). Every scope names its merchant: refreshes
 * of one merchant are serialised (an advisory lock), which is what makes the versions monotonic.
 */
sealed interface Scope {

    String merchantId();

    /** A service or a product offer ({@code catalogue.listing}). */
    record Listing(String kind, String id, String merchantId) implements Scope {
        public Listing {
            if (!kind.equals(ListingDocument.SERVICE) && !kind.equals(ListingDocument.PRODUCT)) {
                throw new IllegalArgumentException("Not a catalogue listing kind: " + kind);
            }
        }
    }

    /** A menu item ({@code food.item_availability}). */
    record Dish(String id, String merchantId) implements Scope {}

    /** Every document of a merchant, including the merchant's own. */
    record Merchant(String merchantId) implements Scope {}

    /**
     * The scope of an event of the indexer's topics: listing events → that listing; item availability → that dish;
     * anything else (menu published, kitchen paused, merchant, storefront, review, availability) → the whole merchant,
     * named by the payload's {@code merchantId} or, on topics keyed by merchant, the aggregate id.
     */
    static Scope of(EventEnvelope event) {
        var merchantId = Objects.requireNonNullElse(event.optionalText("merchantId"), event.aggregateId());
        if (event.type().startsWith("catalogue.listing_")) {
            return new Listing(event.text("kind"), event.aggregateId(), merchantId);
        }
        if (event.type().equals("food.item_availability")) {
            return new Dish(event.aggregateId(), merchantId);
        }
        return new Merchant(merchantId);
    }
}
