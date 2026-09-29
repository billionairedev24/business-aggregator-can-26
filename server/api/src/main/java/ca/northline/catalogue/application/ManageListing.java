package ca.northline.catalogue.application;

/** Listing lifecycle from the Studio: submit for vetting, publish / hide, delete (owner). */
public interface ManageListing {

    ListingView submit(String merchantId, String listingId, String actorId);

    void publish(String merchantId, String listingId);

    void hide(String merchantId, String listingId);

    void delete(String merchantId, String listingId, String actorId);
}
