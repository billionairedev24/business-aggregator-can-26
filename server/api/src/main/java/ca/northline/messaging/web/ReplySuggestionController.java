package ca.northline.messaging.web;

import static ca.northline.shared.security.MerchantPermission.OPERATE;

import ca.northline.messaging.application.BrowseInbox;
import ca.northline.messaging.application.SuggestReplies;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-131: {@code POST /api/v1/merchants/{merchantId}/threads/{threadId}/reply-suggestions} — three reply drafts for the
 * composer, for the roles that may reply ({@code OPERATE}); technicians only for their own jobs' threads. Never sent.
 */
@RestController
@RequiredArgsConstructor
class ReplySuggestionController {

    private final SuggestReplies replies;

    @PostMapping("/api/v1/merchants/{merchantId}/threads/{threadId}/reply-suggestions")
    @RequiresMerchant(OPERATE)
    SuggestReplies.Suggestions suggest(
            @PathVariable String merchantId, @PathVariable String threadId, CurrentMember member, Locale locale) {
        return replies.suggest(merchantId, threadId, new BrowseInbox.Viewer(member.userId(), member.role()), locale);
    }
}
