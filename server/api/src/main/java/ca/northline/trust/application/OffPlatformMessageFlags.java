package ca.northline.trust.application;

import ca.northline.messaging.api.MessageSent;
import ca.northline.shared.Ids;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Messages the detector flagged (contact details masked, or payment outside Northline asked for) become open
 * {@code off_platform_payment} flags for the console trust &amp; safety queue. Idempotent: one flag per message.
 */
@Component
@RequiredArgsConstructor
class OffPlatformMessageFlags {

    private final TrustFlagStore flags;

    @ApplicationModuleListener
    void on(MessageSent event) {
        if (!event.flagged()) {
            return;
        }
        flags.raise(
                Ids.next(),
                "message",
                event.messageId(),
                "off_platform_payment",
                event.merchantId(),
                event.senderId(),
                Map.of("threadId", event.aggregateId(), "eventId", event.eventId(), "senderRole", event.senderRole()));
    }
}
