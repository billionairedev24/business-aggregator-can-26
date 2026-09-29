package ca.northline.booking.application;

import ca.northline.booking.application.QuoteViews.QuoteRequestCard;
import ca.northline.booking.application.QuoteViews.QuoteView;
import ca.northline.booking.domain.Quote;
import ca.northline.booking.domain.QuoteContent;
import java.util.List;

/** Quote use cases (one method each). */
public final class QuoteUseCases {
    private QuoteUseCases() {}

    /** Requests waiting for this merchant, with its draft or latest sent quote. */
    public interface ListQuoteRequests {
        List<QuoteRequestCard> list(String merchantId);
    }

    /** Send the first quote for a request (version 1). Publishes {@code quote.sent}. */
    public interface SendQuote {
        record Command(String merchantId, String requestId, String actorId, QuoteContent content) {}

        QuoteView send(Command command);
    }

    /** "Revise": a new version with the new content; the prior one becomes {@code superseded}. */
    public interface ReviseQuote {
        record Command(String merchantId, String quoteId, String actorId, QuoteContent content) {}

        QuoteView revise(Command command);
    }

    /** "Decline" a request: it disappears from this merchant's list. */
    public interface DeclineQuoteRequest {
        void decline(String merchantId, String requestId, String actorId);
    }

    /** One quote with every line ("View as customer"). */
    public interface ViewQuote {
        QuoteView view(String merchantId, String quoteId);
    }

    /**
     * The customer accepts a quote (consumer app; no Studio endpoint). Publishes {@code quote.accepted} → booking +
     * escrow hold.
     */
    public interface AcceptQuote {
        Quote accept(String quoteId, String customerId);
    }
}
