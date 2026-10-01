package ca.northline.messaging.application;

import ca.northline.ai.api.AiCompletions;
import ca.northline.ai.api.AiCompletions.Caller;
import ca.northline.ai.api.AiCompletions.Request;
import ca.northline.ai.api.AiFeature;
import ca.northline.ai.api.Prompts;
import ca.northline.messaging.domain.SenderRole;
import ca.northline.shared.Conflict;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** {@link SuggestReplies} over the AI port (prompt {@code message-reply}, light model). */
@Service
@RequiredArgsConstructor
public class ReplySuggestions implements SuggestReplies {

    /** The last messages the model sees: enough context, and no more of the conversation than needed. */
    static final int TURNS = 8;

    static final int REPLY_MAX = 600;

    private final AiCompletions ai;
    private final Prompts prompts;
    private final BrowseInbox inbox;
    private final JsonMapper json;

    @Override
    public Suggestions suggest(String merchantId, String threadId, BrowseInbox.Viewer viewer, Locale locale) {
        var thread = inbox.thread(merchantId, threadId, viewer, locale);
        var turns = thread.messages().stream()
                .filter(m -> !m.body().isBlank())
                .map(m -> new Turn(from(m.senderRole()), m.body()))
                .toList();
        if (turns.isEmpty() || !"customer".equals(turns.getLast().from())) {
            throw new Conflict("nothing_to_reply", "There's no customer message to reply to.");
        }
        var refType = thread.thread().refType() == null ? "request" : thread.thread().refType();
        return suggest(merchantId, viewer.userId(), refType, turns, locale);
    }

    @Override
    public Suggestions suggest(String merchantId, String userId, String refType, List<Turn> turns, Locale locale) {
        var recent = turns.subList(Math.max(0, turns.size() - TURNS), turns.size());
        var prompt = prompts.get("message-reply");
        var system = prompt.render(Map.of(
                "refType", refType, "language", locale.getLanguage().equals("fr") ? "Canadian French" : "English"));
        var answer = ai.complete(Request.of(
                                AiFeature.MESSAGE_REPLY,
                                Caller.member(userId, merchantId),
                                prompt,
                                system,
                                "Thread (oldest first): " + json.writeValueAsString(recent))
                        .asJson()
                        .withMaxTokens(500));
        var node = answer.json().orElseThrow(() -> new IllegalStateException("The model's suggestions weren't JSON."));
        var replies = new ArrayList<String>();
        node.path("replies").forEach(r -> {
            if (r.isString() && !r.asString().isBlank() && replies.size() < 3) {
                var text = r.asString().strip();
                replies.add(text.length() > REPLY_MAX ? text.substring(0, REPLY_MAX) : text);
            }
        });
        return new Suggestions(replies, true, answer.model(), prompt.id());
    }

    static String from(SenderRole role) {
        return switch (role) {
            case CUSTOMER -> "customer";
            case MERCHANT -> "business";
            default -> "northline";
        };
    }
}
