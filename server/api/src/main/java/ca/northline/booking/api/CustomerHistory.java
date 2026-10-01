package ca.northline.booking.api;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A customer's own bookings and quote requests, newest first, for the account area's "Orders &amp; bookings" and the
 * home page's "Your week" (S-58). Ids, states and amounts — names come from the merchants and identity APIs.
 */
public interface CustomerHistory {

    /**
     * @param state {@code requested | confirmed | en_route | on_site | completed | signed_off | disputed | cancelled}
     * @param totalCents price + tax as booked
     * @param depositCents what was held when the booking came from a quote with a deposit (else the whole price)
     * @param quoteId the accepted quote it came from, or null for a direct booking
     * @param completedAt when the provider marked it completed (the 48 h release clock), or null
     * @param paid money is held in escrow for it
     */
    record BookingSummary(
            String id,
            String ref,
            String merchantId,
            @Nullable String memberUserId,
            String title,
            String state,
            Instant startsAt,
            Instant endsAt,
            long totalCents,
            long depositCents,
            @Nullable String quoteId,
            @Nullable Instant completedAt,
            boolean paid,
            Instant createdAt) {

        /** Still ahead of the customer or under way. */
        public boolean active() {
            return switch (state) {
                case "requested", "confirmed", "en_route", "on_site" -> true;
                default -> false;
            };
        }
    }

    /**
     * A quote request that hasn't turned into a booking yet.
     *
     * @param quoted providers who sent a quote (their latest version is still open)
     * @param openQuotes the latest open (sent or viewed, not expired) quote of each provider, cheapest first
     * @param lowestCents the cheapest open quote's total, or null while none has arrived
     * @param declined the customer declined every quote they received
     */
    record RequestSummary(
            String id,
            String ref,
            String title,
            Instant createdAt,
            @Nullable Instant preferredAt,
            @Nullable Instant expiresAt,
            List<String> merchantIds,
            List<String> quoted,
            List<OpenQuote> openQuotes,
            @Nullable Long lowestCents,
            boolean declined) {

        public RequestSummary {
            merchantIds = List.copyOf(merchantIds);
            quoted = List.copyOf(quoted);
            openQuotes = List.copyOf(openQuotes);
        }
    }

    record OpenQuote(String quoteId, String merchantId) {}

    /** The customer's bookings, latest start first (at most {@code limit}). */
    List<BookingSummary> bookings(String customerId, int limit);

    /** The customer's quote requests without an accepted quote, newest first (at most {@code limit}). */
    List<RequestSummary> openRequests(String customerId, int limit);
}
