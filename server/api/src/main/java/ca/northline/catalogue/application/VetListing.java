package ca.northline.catalogue.application;

/** Runs the automated vetting checks on a pending listing: approve and publish, or flag for manual review. */
public interface VetListing {
    void vet(String listingId);
}
