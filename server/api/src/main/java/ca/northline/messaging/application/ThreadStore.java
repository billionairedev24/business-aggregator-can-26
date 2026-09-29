package ca.northline.messaging.application;

import ca.northline.messaging.application.BrowseInbox.Message;
import ca.northline.messaging.application.BrowseInbox.QuickReply;
import ca.northline.messaging.application.BrowseInbox.ThreadSummary;
import ca.northline.messaging.domain.InboxScope;
import ca.northline.messaging.domain.Portal;
import ca.northline.messaging.domain.SenderRole;
import ca.northline.messaging.domain.ThreadKind;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: threads and messages ({@code messaging.threads}, {@code messaging.messages}, quick-reply macros). */
public interface ThreadStore {

    record NewThread(
            String id,
            String merchantId,
            ThreadKind kind,
            String refType,
            String refId,
            @Nullable String refCode,
            @Nullable String counterpartId,
            String counterpartName,
            @Nullable String subject,
            @Nullable String assigneeId,
            Instant createdAt) {}

    record NewMessage(
            String id,
            String threadId,
            SenderRole senderRole,
            String senderId,
            @Nullable String senderName,
            String body,
            List<String> attachmentIds,
            @Nullable String templateKey,
            Instant at,
            boolean flagged) {}

    /** A booking / order / dispute a customer or support thread is linked to. */
    record LinkedRecord(
            String refType, String refId, @Nullable String refCode, String counterpartName) {}

    /** Customer and support threads the scope allows, most recent first. */
    List<ThreadSummary> list(String merchantId, InboxScope scope, String userId);

    Optional<ThreadSummary> find(String merchantId, String threadId, InboxScope scope, String userId);

    Optional<String> findByRef(String merchantId, String refType, String refId);

    void create(NewThread thread);

    List<Message> messages(String threadId);

    /** Appends the message and moves the thread's {@code last_message_at}; a business message also marks it read. */
    void add(NewMessage message);

    void markRead(String threadId, Instant at);

    /** Threads the scope allows with a customer or Northline message the business hasn't opened yet. */
    int unreadThreads(String merchantId, InboxScope scope, String userId);

    List<LinkedRecord> linkedRecords(String merchantId, int limit);

    List<QuickReply> quickReplies(Portal portal, String lang);

    /** The template of a quick reply available to {@code portal}, in {@code lang}. */
    Optional<String> quickReplyText(Portal portal, String key, String lang);
}
