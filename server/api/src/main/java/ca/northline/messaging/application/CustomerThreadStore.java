package ca.northline.messaging.application;

import ca.northline.messaging.api.CustomerConversations.CustomerThread;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Outbound port (S-153): customer threads as their customer sees them ({@code messaging.threads.counterpart_id}). */
public interface CustomerThreadStore {

    /** The customer's customer-kind threads, most recent first. */
    List<CustomerThread> list(String customerId);

    Optional<CustomerThread> find(String customerId, String threadId);

    void markRead(String threadId, Instant at);

    /** The counterpart of a customer thread. */
    Optional<String> customerOf(String threadId);
}
