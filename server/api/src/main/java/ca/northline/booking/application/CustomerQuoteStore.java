package ca.northline.booking.application;

import ca.northline.booking.api.CustomerQuotes.Acceptance;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port over {@code booking.quote_requests} and {@code booking.quote_acceptances} as the customer sees them. */
public interface CustomerQuoteStore {

    /** Inserts the request; returns its number (the {@code QT-} reference). */
    long insertRequest(
            String id,
            String customerId,
            String categoryId,
            Map<String, Object> details,
            List<String> merchantIds,
            Instant createdAt,
            Instant respondBy,
            Instant expiresAt);

    /** The customer's own request. */
    Optional<Request> request(String customerId, String requestId);

    /** Providers who declined the request. */
    List<String> declinedBy(String requestId);

    /** {@code sent} → {@code viewed}, once. */
    void markViewed(String quoteId, Instant at);

    void upsertAcceptance(Acceptance acceptance);

    Optional<Acceptance> acceptance(String quoteId);

    void markAccepted(String quoteId, Instant at);

    /** {@code details} carries {@code title}, {@code description}, {@code area}, {@code preferredAt}. */
    record Request(
            String id,
            long number,
            String customerId,
            @Nullable String categoryId,
            String title,
            @Nullable String description,
            @Nullable String area,
            @Nullable Instant preferredAt,
            Instant createdAt,
            @Nullable Instant respondBy,
            @Nullable Instant expiresAt,
            List<String> merchantIds) {

        public String ref() {
            return "QT-" + number;
        }
    }
}
