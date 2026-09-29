package ca.northline.messaging.web;

import static ca.northline.shared.security.MerchantPermission.OPERATE;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.messaging.application.BrowseInbox;
import ca.northline.messaging.application.BrowseInbox.Message;
import ca.northline.messaging.application.BrowseInbox.ThreadDetail;
import ca.northline.messaging.application.BrowseInbox.ThreadSummary;
import ca.northline.messaging.application.BrowseInbox.Viewer;
import ca.northline.messaging.application.SendMessage;
import ca.northline.messaging.web.MessagingRequests.MessageRequest;
import ca.northline.shared.ListResponse;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.validation.Valid;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Studio › Messages: {@code GET /threads} (scoped by role), {@code GET /threads/{id}}, {@code POST /threads/{id}/read},
 * {@code POST /threads/{id}/messages}. The Studio polls these; server-sent events are a later step (DECISIONS.md).
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/threads")
@RequiredArgsConstructor
class MessagingThreadController {

    private final BrowseInbox inbox;
    private final SendMessage sendMessage;

    @GetMapping
    @RequiresMerchant(VIEW)
    ListResponse<ThreadSummary> list(@PathVariable String merchantId, CurrentMember member) {
        return new ListResponse<>(inbox.threads(merchantId, viewer(member)));
    }

    @GetMapping("/{threadId}")
    @RequiresMerchant(VIEW)
    ThreadDetail get(
            @PathVariable String merchantId, @PathVariable String threadId, CurrentMember member, Locale locale) {
        return inbox.thread(merchantId, threadId, viewer(member), locale);
    }

    @PostMapping("/{threadId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresMerchant(VIEW)
    void read(@PathVariable String merchantId, @PathVariable String threadId, CurrentMember member) {
        inbox.markRead(merchantId, threadId, viewer(member));
    }

    @PostMapping("/{threadId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresMerchant(OPERATE)
    Message send(
            @PathVariable String merchantId,
            @PathVariable String threadId,
            @Valid @RequestBody MessageRequest body,
            CurrentMember member) {
        return sendMessage.send(new SendMessage.Command(
                merchantId, threadId, viewer(member), body.body(), body.files(), body.templateKey()));
    }

    private static Viewer viewer(CurrentMember member) {
        return new Viewer(member.userId(), member.role());
    }
}
