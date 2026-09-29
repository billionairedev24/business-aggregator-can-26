package ca.northline.catalogue.application;

import ca.northline.catalogue.api.ListingSubmitted;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Runs the automated checks after a submission commits (async, own transaction, retried from the event registry).
 * Idempotent: a listing that is no longer pending is left alone.
 */
@Component
@RequiredArgsConstructor
class VettingOnSubmit {

    private final VetListing vetListing;

    @ApplicationModuleListener
    void on(ListingSubmitted submitted) {
        vetListing.vet(submitted.aggregateId());
    }
}
