package ca.northline.messaging.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * S-153: the customer's side of the Studio's customer threads — "Ask a question" on a quote, and messages about a
 * booking. The thread is the one the business sees in Messages (one per business and job, {@link Conversations}); the
 * customer reads and writes only their own ({@code counterpart_id}). Customer messages are masked and checked for
 * off-platform payment exactly like the business's (S-93) and publish {@link MessageSent}, which the Studio's live
 * stream and the notifications worker pick up. Text only (no attachments from customers yet).
 */
public interface CustomerConversations {

    String MESSAGE_REQUIRED = "Write a message.";

    /**
     * Opens (or finds) the customer thread of a job with one business. Idempotent per {@code (merchantId, refType,
     * refId)}.
     *
     * @param refType {@code quote} (refId = the quote request) or {@code booking}
     * @param refCode what both sides see: {@code QT-3104}, {@code BK-7712}
     * @param customerName the display form the Studio shows ("Amara O.")
     * @param subject short context ("Alternator replacement")
     */
    record Open(
            String merchantId,
            String refType,
            String refId,
            String refCode,
            String customerId,
            String customerName,
            @Nullable String subject,
            @Nullable String assigneeId) {}

    String open(Open command);

    /**
     * One of the customer's threads.
     *
     * @param unread a message from the business newer than the customer's last read
     */
    record CustomerThread(
            String id,
            String merchantId,
            String refType,
            String refId,
            @Nullable String refCode,
            @Nullable String subject,
            @Nullable Instant lastMessageAt,
            @Nullable String lastMessage,
            boolean unread) {}

    /**
     * @param from {@code me} (the customer), {@code business} or {@code northline} (an agent or the system)
     * @param flagged contact details were masked or payment outside Northline was asked for (the nudge shows)
     */
    record Message(String id, String from, String body, Instant at, boolean flagged) {}

    record ThreadView(CustomerThread thread, List<Message> messages) {
        public ThreadView {
            messages = List.copyOf(messages);
        }
    }

    /** The customer's threads, most recent first. */
    List<CustomerThread> threads(String customerId);

    /** The customer's own thread with its messages; empty for anyone else's. */
    Optional<ThreadView> thread(String customerId, String threadId);

    /** The thread of a job with a business, when one was opened. */
    Optional<String> threadOf(String merchantId, String refType, String refId);

    /** Sends a message in the customer's own thread (404 for anyone else's); 422 for an empty or too long text. */
    Message send(String customerId, String threadId, String body);

    /** The customer opened the thread. */
    void markRead(String customerId, String threadId);

    /** The customer of a customer thread (the live stream's addressee); empty for other threads. */
    Optional<String> customerOf(String threadId);
}
