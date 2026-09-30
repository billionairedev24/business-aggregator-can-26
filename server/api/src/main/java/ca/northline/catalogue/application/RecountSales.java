package ca.northline.catalogue.application;

import java.time.Duration;

/**
 * The Listings table's "30-day sales" (S-38): units sold per offer and bookings per service over the last 30 days,
 * counted by the orders and booking modules and stored on the listing.
 */
public interface RecountSales {

    Duration WINDOW = Duration.ofDays(30);

    void recount(String merchantId);

    /** Daily: recounts every merchant that still shows sales, so sales older than 30 days drop out. */
    int recountAll();
}
