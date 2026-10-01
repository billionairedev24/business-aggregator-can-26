package ca.northline.messaging.api;

import java.time.Instant;
import java.util.List;

/**
 * S-133: messages between businesses and customers, with their text, for the trust &amp; safety screen. Only customer
 * threads and only messages a business or a customer wrote (not Northline agents or the system); the text is as stored
 * (phone numbers and emails already masked). No names, thread subject or counterpart.
 */
public interface MessageTexts {

    /** Messages written after ({@code at}, {@code id}), oldest first, at most {@code limit}. */
    List<MessageText> after(Instant at, String id, int limit);

    /**
     * @param senderRole who wrote it: "merchant" (the business) or "customer"
     * @param detectorFlagged the off-platform detector already flagged it (masked contact, payment words)
     */
    record MessageText(
            String messageId,
            String threadId,
            String merchantId,
            String senderRole,
            String body,
            boolean detectorFlagged,
            Instant at) {}
}
