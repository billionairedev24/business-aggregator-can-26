package ca.northline.catalogue.application;

import java.time.Instant;
import java.util.List;

/** Outbound port (S-133): listings (services ∪ offers) by submission time, for the trust &amp; safety screen. */
public interface SubmittedListings {

    /** Ids of listings submitted after ({@code at}, {@code id}), oldest first. */
    List<Submitted> after(Instant at, String id, int limit);

    record Submitted(String listingId, Instant submittedAt) {}
}
