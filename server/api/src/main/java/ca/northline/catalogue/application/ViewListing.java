package ca.northline.catalogue.application;

/** One listing for the editor. Throws {@link ca.northline.shared.NotFound}. */
public interface ViewListing {
    ListingView view(String merchantId, String listingId);
}
