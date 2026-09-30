package ca.northline.booking.api;

import java.time.Instant;
import java.util.Map;

/** Bookings per catalogue service, for the Listings table's 30-day sales (S-38). */
public interface ServiceSales {

    /**
     * Bookings of each of the merchant's services made in [from, to), cancelled ones excluded. Services without
     * bookings are absent.
     */
    Map<String, Long> bookingsByService(String merchantId, Instant from, Instant to);
}
