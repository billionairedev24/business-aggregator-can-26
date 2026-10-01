package ca.northline.messaging.application;

import ca.northline.messaging.api.CustomerCaseDesk.Attachment;
import ca.northline.messaging.api.CustomerCaseDesk.CaseThread;
import ca.northline.messaging.domain.TicketPriority;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: customer cases ({@code messaging.tickets} + a {@code case} thread) and their uploads (V164). */
public interface CustomerCaseStore {

    record NewTicket(
            String id,
            String customerId,
            String topic,
            String subject,
            TicketPriority priority,
            boolean urgent,
            Instant slaDueAt,
            String refType,
            String refId,
            String refLabel,
            String lang,
            String contextJson,
            Instant createdAt) {}

    /** Inserts the ticket and returns its number (HD-number). */
    int insert(NewTicket ticket);

    void thread(String threadId, String ticketId, String code, String subject, Instant at);

    void message(String threadId, String senderRole, String senderId, String body, List<String> attachmentIds, Instant at);

    Optional<CaseThread> forRefund(String customerId, String refundId);

    Optional<CaseThread> find(String customerId, String ticketId);

    /** The ticket's thread id, when the ticket is the customer's. */
    Optional<String> threadOf(String customerId, String ticketId);

    void touched(String ticketId, Instant at);

    record StoredUpload(
            String id, String customerId, String storageKey, String fileName, String contentType, long byteSize, Instant at) {}

    void insertUpload(StoredUpload upload);

    Optional<StoredUpload> upload(String customerId, String id);

    /** The ids among {@code ids} that the customer uploaded. */
    List<String> ownUploads(String customerId, Collection<String> ids);

    List<Attachment> attachments(Collection<String> ids);

    @Nullable
    String stateOf(String ticketId);
}
