package ca.northline.booking.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The customer's side of quotes (consumer web, S-56): asking several providers at once, reading their itemized,
 * versioned quotes and accepting one. The merchant side (composer, revisions) is {@code QuoteController} in Studio.
 * A customer only ever sees their own requests, and never a draft.
 */
public interface CustomerQuotes {

    /**
     * @param merchantIds the providers asked (1–3, already checked by the caller)
     * @param title what the job is ("Alternator replacement", "Mocktail bar · 40 guests")
     * @param area the zone or city the work is in ("Beltline"): never a street address
     * @param details the answers the providers need (vehicle, event date, guests…): no access codes or phone numbers
     */
    record NewRequest(
            String customerId,
            String categoryId,
            List<String> merchantIds,
            String title,
            String description,
            @Nullable String area,
            @Nullable Instant preferredAt,
            Map<String, Object> details) {
        public NewRequest {
            merchantIds = List.copyOf(merchantIds);
            details = Map.copyOf(details);
        }
    }

    /** @param ref the reference both sides see ({@code QT-3104}) */
    record RequestRef(String id, String ref, Instant respondBy, Instant expiresAt) {}

    /** Stores the request; the providers find it in their Studio "Quote requests" column. */
    RequestRef request(NewRequest request);

    /** One line as the provider wrote it; {@code amountCents} is negative for a discount. */
    record Line(
            String kind,
            String description,
            @Nullable String note,
            BigDecimal qty,
            long unitCents,
            long amountCents,
            boolean taxable) {}

    /** Every version the customer received from that provider for the request, newest first. */
    record Version(String quoteId, int version, String state, long totalCents, @Nullable Instant sentAt) {}

    /**
     * One version of one provider's quote.
     *
     * @param state {@code sent | viewed | accepted | declined | expired | superseded}
     * @param expired {@code validUntil} has passed (the state may not say so yet)
     * @param warranty {@code none | labour_90d | parts_labour_12m | manufacturer}
     * @param depositKind {@code none | parts_upfront | pct}
     * @param currentQuoteId the provider's latest version for the request (this one, unless it was revised)
     */
    record CustomerQuote(
            String id,
            String requestId,
            String ref,
            String merchantId,
            int version,
            String state,
            boolean expired,
            String scope,
            @Nullable String exclusions,
            @Nullable Instant proposedAt,
            @Nullable Integer durationMin,
            String warranty,
            String depositKind,
            @Nullable Integer depositBps,
            List<Line> lines,
            long subtotalCents,
            int taxBps,
            long taxCents,
            long totalCents,
            long depositCents,
            @Nullable Instant sentAt,
            @Nullable Instant validUntil,
            List<Version> versions,
            String currentQuoteId) {
        public CustomerQuote {
            lines = List.copyOf(lines);
            versions = List.copyOf(versions);
        }

        /** Can still be accepted. */
        public boolean open() {
            return !expired && ("sent".equals(state) || "viewed".equals(state));
        }
    }

    /**
     * A request with each provider's latest quote.
     *
     * @param declinedBy providers who said no
     */
    record CustomerRequest(
            String id,
            String ref,
            @Nullable String categoryId,
            String title,
            @Nullable String description,
            @Nullable String area,
            @Nullable Instant preferredAt,
            Instant createdAt,
            @Nullable Instant respondBy,
            @Nullable Instant expiresAt,
            List<String> merchantIds,
            List<String> declinedBy,
            List<CustomerQuote> quotes) {
        public CustomerRequest {
            merchantIds = List.copyOf(merchantIds);
            declinedBy = List.copyOf(declinedBy);
            quotes = List.copyOf(quotes);
        }
    }

    /** The customer's own request; empty for anyone else's. */
    Optional<CustomerRequest> request(String customerId, String requestId);

    /** The customer's own quote (never a draft); empty for anyone else's. */
    Optional<CustomerQuote> quote(String customerId, String quoteId);

    /** The customer opened the quote: {@code sent} → {@code viewed} (the provider sees it was read). */
    void markViewed(String customerId, String quoteId);

    /** "Decline": the provider is told; other quotes stay open. 409 when it's no longer open. */
    void decline(String customerId, String quoteId);

    /** What was prepared for the payment, between "Accept · hold $…" and the card confirmation. */
    record Acceptance(
            String quoteId,
            String customerId,
            String bookingId,
            String paymentIntent,
            long amountCents,
            long taxCents,
            @Nullable Instant acceptedAt) {}

    /** Records (or replaces, while not accepted) the payment prepared for accepting the quote. */
    void prepareAcceptance(Acceptance acceptance);

    Optional<Acceptance> acceptance(String customerId, String quoteId);

    /**
     * Accepts the quote ({@code quote.accepted}); the caller has checked the escrow hold. 409 {@code quote_revised} when
     * a newer version replaced it, {@code quote_expired}, {@code quote_state} otherwise.
     */
    CustomerQuote accept(String customerId, String quoteId);
}
