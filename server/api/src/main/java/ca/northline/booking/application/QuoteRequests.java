package ca.northline.booking.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port over {@code booking.quote_requests} as seen by one merchant. */
public interface QuoteRequests {

    /** Requests addressed to the merchant, not declined by it and not expired, soonest respond-by first. */
    List<Request> open(String merchantId, Instant now);

    Optional<Request> find(String merchantId, String requestId);

    void decline(String requestId, String merchantId, String actorId, Instant at);

    /**
     * A customer's request. {@code title}, {@code body}, {@code area} and {@code preferredAt} come from {@code details}.
     */
    record Request(
            String id,
            long number,
            @Nullable String customerId,
            String title,
            @Nullable String body,
            @Nullable String area,
            @Nullable Instant preferredAt,
            Instant createdAt,
            @Nullable Instant respondBy,
            @Nullable Instant expiresAt,
            boolean declined) {

        /** Reference shown to both sides and kept by every version: {@code QT-3104}. */
        public String quoteRef() {
            return "QT-" + number;
        }
    }
}
