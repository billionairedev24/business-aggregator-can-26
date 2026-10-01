package ca.northline.catalogue.api;

import ca.northline.shared.Backlog;
import ca.northline.shared.MerchantScope;

/** Listings waiting for a human in the console's vetting queue (S-91 overview; S-92 builds the queue). */
public interface VettingQueue {

    /** Products and services pending vetting with at least one rule tripped, oldest by submission. */
    Backlog flagged(MerchantScope scope);
}
