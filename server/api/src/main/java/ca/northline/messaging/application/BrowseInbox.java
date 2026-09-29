package ca.northline.messaging.application;

import ca.northline.messaging.domain.SenderRole;
import ca.northline.messaging.domain.ThreadKind;
import ca.northline.shared.security.MerchantRole;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Messages screen: the thread list, one open thread with its quick replies, and the shared-inbox read mark. */
public interface BrowseInbox {

    /** The team member looking: technicians see only their own jobs' threads (see {@code InboxScope}). */
    record Viewer(String userId, MerchantRole role) {}

    record ThreadSummary(
            String id,
            ThreadKind kind,
            String counterpartName,
            @Nullable String subject,
            @Nullable String refType,
            @Nullable String refId,
            @Nullable String refCode,
            @Nullable String assigneeId,
            @Nullable Instant lastMessageAt,
            @Nullable String lastMessage,
            boolean unread) {}

    record Attachment(String id, String fileName, String contentType, long byteSize) {}

    record Message(
            String id,
            SenderRole senderRole,
            @Nullable String senderName,
            String body,
            List<Attachment> attachments,
            Instant at,
            boolean flagged,
            @Nullable String templateKey) {}

    record QuickReply(String key, String text) {}

    record ThreadDetail(ThreadSummary thread, List<Message> messages, List<QuickReply> quickReplies) {}

    List<ThreadSummary> threads(String merchantId, Viewer viewer);

    ThreadDetail thread(String merchantId, String threadId, Viewer viewer, Locale locale);

    /** The team opened the thread: its customer messages no longer count as unread (for the whole business). */
    void markRead(String merchantId, String threadId, Viewer viewer);
}
