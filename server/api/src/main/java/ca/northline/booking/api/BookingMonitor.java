package ca.northline.booking.api;

import ca.northline.shared.MerchantScope;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Service bookings for the console's orders monitor (S-81): the recent ones and every one still open, of the
 * businesses in scope. Ids, references, states, times and money only — names are looked up by the caller.
 */
public interface BookingMonitor {

    /**
     * Bookings made since {@code since} or not finished yet (not signed off or cancelled), newest first, at most
     * {@code limit}; with {@code ref}, only references starting with it (case-insensitive).
     */
    List<MonitoredBooking> bookings(MerchantScope scope, Instant since, @Nullable String ref, int limit);

    /**
     * S-82: per business, the price of the bookings made in [from, to) (cancelled out) and their number. Businesses
     * without bookings are absent.
     */
    java.util.Map<String, Sales> salesByMerchant(java.util.Collection<String> merchantIds, Instant from, Instant to);

    record Sales(long gmvCents, long bookings) {}

    /** @param state {@code requested} … {@code cancelled} */
    record MonitoredBooking(
            String id,
            @Nullable String ref,
            String state,
            @Nullable String customerId,
            String merchantId,
            long priceCents,
            Instant createdAt,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt) {}
}
