package ca.northline.hire.application;

import ca.northline.booking.api.CustomerQuotes.CustomerQuote;
import ca.northline.hire.application.BookingCheckout.Confirmation;
import ca.northline.hire.domain.QuoteAsk;
import ca.northline.hire.domain.QuoteVisit;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The quote side of the Services journey (S-56): ask several providers, compare, accept by paying the deposit. */
public final class QuoteFlow {
    private QuoteFlow() {}

    /** "Send request to N providers". */
    public interface RequestQuotes {
        Requested request(String customerId, QuoteAsk ask);
    }

    /** The compare page: every provider asked, with their latest quote when it's in. */
    public interface CompareQuotes {
        Comparison compare(String customerId, String requestId, String lang);
    }

    /** One quote with every line, its versions and the other quotes for the same request; marks it viewed. */
    public interface ViewQuote {
        QuotePage quote(String customerId, String quoteId, String lang);
    }

    public interface DeclineQuote {
        void decline(String customerId, String quoteId);
    }

    /**
     * "Accept · hold $…": the S-51 step-up rule, then the deposit's manual-capture PaymentIntent (Stripe.js confirms the
     * card when the gateway is Stripe; the fake gateway authorizes at once). {@link ConfirmAcceptance} follows.
     */
    public interface StartAcceptance {
        Acceptance start(
                String customerId,
                String quoteId,
                QuoteVisit visit,
                @Nullable String clientKey,
                boolean mfa,
                @Nullable String stepUpProof);
    }

    /** The card is authorized: record the escrow hold, accept the quote ({@code quote.accepted}) and book it. */
    public interface ConfirmAcceptance {
        Confirmation confirm(String customerId, String quoteId, QuoteVisit visit);
    }

    public record Requested(String requestId, String ref, Instant respondBy, Instant expiresAt, int providers) {}

    /**
     * A provider as the quote screens show it.
     *
     * @param onTimePct on-time arrival (percent), null before the first nightly score
     */
    public record ProviderSummary(
            String merchantId,
            String slug,
            String name,
            String tier,
            String brandColor,
            double rating,
            int reviewCount,
            @Nullable Double onTimePct,
            @Nullable Double disputePct,
            List<String> verifiedFacts) {}

    /**
     * One row of the compare table.
     *
     * @param status {@code quoted | waiting | declined}
     * @param quote the provider's latest version, when {@code quoted}
     */
    public record Offer(ProviderSummary provider, String status, @Nullable CustomerQuote quote) {}

    public record Comparison(
            String requestId,
            String ref,
            @Nullable String categorySlug,
            String title,
            @Nullable String description,
            @Nullable String area,
            @Nullable Instant preferredAt,
            Instant createdAt,
            @Nullable Instant respondBy,
            @Nullable Instant expiresAt,
            List<Offer> offers) {}

    /** @param others the other providers' latest quotes for the same request (compare link) */
    public record QuotePage(
            CustomerQuote quote,
            ProviderSummary provider,
            String title,
            @Nullable String area,
            @Nullable String bookingId,
            List<Other> others) {}

    public record Other(String quoteId, String providerName, long totalCents, String state) {}

    /**
     * @param amountCents what is held now (the deposit, or the whole quote when it asks for none) before tax
     * @param status {@code requires_action} / {@code requires_payment_method} (Stripe.js confirms), {@code authorized}
     */
    public record Acceptance(
            String quoteId,
            String bookingId,
            long amountCents,
            long taxCents,
            long totalCents,
            String status,
            @Nullable String paymentIntent,
            @Nullable String clientSecret,
            String provider,
            @Nullable String publishableKey) {}
}
