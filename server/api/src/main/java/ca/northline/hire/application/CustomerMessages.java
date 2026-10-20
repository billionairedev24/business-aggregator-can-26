package ca.northline.hire.application;

import ca.northline.messaging.api.CustomerConversations;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * S-153: the customer's messages with providers — "Ask a question" on a quote request (before or after the quote) and
 * messages about a booking. Only the customer's own threads; someone else's is a 404.
 */
public interface CustomerMessages {

    String NOT_ASKED = "This provider wasn't asked for this quote.";

    /**
     * @param businessName / {@code businessSlug} the provider (its page), empty when it is no longer published
     * @param href where the job lives on the consumer site ({@code /quotes/requests/<id>}, {@code /bookings/<id>})
     */
    record ThreadRow(
            String id,
            String refType,
            String refId,
            @Nullable String refCode,
            @Nullable String subject,
            String businessName,
            String businessSlug,
            String href,
            @Nullable Instant lastMessageAt,
            @Nullable String lastMessage,
            boolean unread) {}

    record ThreadPage(ThreadRow thread, List<CustomerConversations.Message> messages) {
        public ThreadPage {
            messages = List.copyOf(messages);
        }
    }

    List<ThreadRow> threads(String customerId);

    ThreadPage thread(String customerId, String threadId);

    /**
     * Opens (or finds) the thread with one provider about one job.
     *
     * @param refType {@code quote} (refId = the quote request; {@code merchantId} = one of the providers asked) or
     *     {@code booking} (the merchant is the booking's)
     */
    ThreadPage open(String customerId, String refType, String refId, @Nullable String merchantId);

    CustomerConversations.Message send(String customerId, String threadId, String body);

    void read(String customerId, String threadId);
}
