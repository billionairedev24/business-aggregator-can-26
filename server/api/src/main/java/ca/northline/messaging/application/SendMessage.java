package ca.northline.messaging.application;

import ca.northline.messaging.application.BrowseInbox.Message;
import ca.northline.messaging.application.BrowseInbox.Viewer;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The composer: send a message (text and/or attachments, optionally from a quick reply) in a customer thread. */
public interface SendMessage {

    record Command(
            String merchantId,
            String threadId,
            Viewer viewer,
            @Nullable String body,
            List<String> attachmentIds,
            @Nullable String templateKey) {}

    Message send(Command command);
}
