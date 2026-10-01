package ca.northline.catalogue.application;

import org.jspecify.annotations.Nullable;

/**
 * Price and stock only — the bulk "price &amp; stock" template for one listing (S-127: the MCP tool
 * {@code update_listing_price_stock}, partners' inventory sync, a quick edit). A product takes both (a product with
 * variants is changed in the editor); a service takes a price only. A new price on an approved listing sends it back
 * to vetting (S-39); stock alone doesn't. A field left out keeps its value.
 */
public interface QuickUpdateListing {

    record Command(
            String merchantId,
            String listingId,
            @Nullable Long priceCents,
            @Nullable Integer stock,
            String actorId) {}

    ListingView update(Command command);
}
