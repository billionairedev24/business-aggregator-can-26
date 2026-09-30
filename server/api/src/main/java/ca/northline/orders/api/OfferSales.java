package ca.northline.orders.api;

import java.time.Instant;
import java.util.Map;

/** Units sold per catalogue offer, for the Listings table's 30-day sales (S-38). */
public interface OfferSales {

    /**
     * Units of each of the merchant's offers on goods orders placed in [from, to). Cancelled and refunded orders and
     * refunded lines don't count. Offers without sales are absent.
     */
    Map<String, Long> unitsByOffer(String merchantId, Instant from, Instant to);
}
