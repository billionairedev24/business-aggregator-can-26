package ca.northline.shared;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Purchase activity for the console's analytics (S-95), implemented by each module that sells (orders, booking). Only
 * opaque customer ids and instants cross the module boundary — no name, contact or address — and the console turns
 * them into counts before anything leaves the server.
 */
public interface CustomerActivity {

    /** Every paid purchase in [from, to) at a business in {@code scope}. */
    List<Purchase> purchases(MerchantScope scope, Instant from, Instant to);

    /** Sales in [from, to) by what was sold (offer id for goods, service id for bookings), in cents. */
    Map<String, Long> salesByListing(MerchantScope scope, Instant from, Instant to);

    record Purchase(String customerId, Instant at) {}
}
