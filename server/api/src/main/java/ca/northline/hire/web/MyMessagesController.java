package ca.northline.hire.web;

import ca.northline.hire.application.CustomerMessages;
import ca.northline.hire.application.CustomerMessages.ThreadPage;
import ca.northline.hire.application.CustomerMessages.ThreadRow;
import ca.northline.messaging.api.CustomerConversations;
import ca.northline.shared.ListResponse;
import ca.northline.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-153: the customer's messages with providers (consumer web and app). {@code POST /me/threads} opens the thread of
 * a quote request with one of the providers asked ("Ask a question"), or of a booking; the Studio sees the same thread
 * in Messages. Live: {@code GET /me/live} signals {@code message} when the business writes.
 */
@RestController
@RequiredArgsConstructor
class MyMessagesController {

    static final String REF_REQUIRED = "Choose a quote request or a booking.";

    private final CustomerMessages messages;

    record OpenRequest(
            @NotBlank(message = REF_REQUIRED) String refType,
            @NotBlank(message = REF_REQUIRED) String refId,
            @Nullable String merchantId) {}

    record SendRequest(
            @NotBlank(message = CustomerConversations.MESSAGE_REQUIRED)
            @Size(max = 2000, message = "Keep messages under 2,000 characters.")
            String body) {}

    @GetMapping("/api/v1/me/threads")
    ListResponse<ThreadRow> threads(CurrentUser user) {
        return new ListResponse<>(messages.threads(user.userId()));
    }

    @PostMapping("/api/v1/me/threads")
    ThreadPage open(@Valid @RequestBody OpenRequest body, CurrentUser user) {
        return messages.open(user.userId(), body.refType(), body.refId(), body.merchantId());
    }

    @GetMapping("/api/v1/me/threads/{threadId}")
    ThreadPage thread(@PathVariable String threadId, CurrentUser user) {
        return messages.thread(user.userId(), threadId);
    }

    @PostMapping("/api/v1/me/threads/{threadId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    CustomerConversations.Message send(
            @PathVariable String threadId, @Valid @RequestBody SendRequest body, CurrentUser user) {
        return messages.send(user.userId(), threadId, body.body());
    }

    @PostMapping("/api/v1/me/threads/{threadId}/read")
    ResponseEntity<Void> read(@PathVariable String threadId, CurrentUser user) {
        messages.read(user.userId(), threadId);
        return ResponseEntity.noContent().build();
    }
}
