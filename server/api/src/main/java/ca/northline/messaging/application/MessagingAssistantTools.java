package ca.northline.messaging.application;

import ca.northline.ai.api.AssistantTool;
import ca.northline.shared.security.MerchantPermission;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * S-130: the Studio assistant's inbox tool, over the Messages screen's {@link BrowseInbox} (technicians see only their
 * own jobs' threads, as on the screen). Threads carry the ref, subject, kind, unread flag and last activity — not the
 * customer's name or the message bodies.
 */
final class MessagingAssistantTools {
    private MessagingAssistantTools() {}

    @Component
    @RequiredArgsConstructor
    static final class ListThreadsTool implements AssistantTool {
        private final BrowseInbox inbox;

        @Override
        public String name() {
            return "list_threads";
        }

        @Override
        public String description() {
            return "Message threads with customers and Northline support: ref (booking/order), subject, kind"
                    + " (customer, support, case), whether it has unread customer messages, and the last activity time.";
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of("unreadOnly", Map.of("type", "boolean", "description", "Only threads waiting for a reply"));
        }

        @Override
        public MerchantPermission permission() {
            return MerchantPermission.VIEW;
        }

        @Override
        public String screen() {
            return "messages";
        }

        @Override
        public Result run(Call call) {
            var unreadOnly = call.args().path("unreadOnly").asBoolean(false);
            var threads = inbox.threads(call.merchantId(), new BrowseInbox.Viewer(call.userId(), call.role())).stream()
                    .filter(t -> !unreadOnly || t.unread())
                    .limit(40)
                    .map(t -> {
                        var m = new LinkedHashMap<String, Object>();
                        m.put("ref", t.refCode());
                        m.put("subject", t.subject());
                        m.put("kind", t.kind());
                        m.put("unread", t.unread());
                        m.put(
                                "lastMessageAt",
                                t.lastMessageAt() == null
                                        ? null
                                        : t.lastMessageAt()
                                                .atZone(call.zone())
                                                .toOffsetDateTime()
                                                .toString());
                        return m;
                    })
                    .toList();
            var unread = threads.stream()
                    .filter(t -> Boolean.TRUE.equals(t.get("unread")))
                    .count();
            return new Result(Map.of("unread", unread, "threads", threads), "threads → " + unread + " unread");
        }
    }
}
