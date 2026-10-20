package ca.northline.studio.web;

import ca.northline.shared.security.CurrentUser;
import ca.northline.studio.application.StudioLive;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * S-153: the customer's live stream — the same signals and bus as the Studio's (S-68), keyed by the signed-in
 * customer. Today it carries {@code message} (data {@code {"ref": threadId}}) when the business, or Northline, writes
 * in one of the customer's threads; the consumer web and app refetch the thread with their own session. Ids only.
 */
@RestController
@RequiredArgsConstructor
class CustomerLiveController {

    private final LiveStreams streams;

    @GetMapping(path = "/api/v1/me/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "The customer's live stream (server-sent events)", description = """
                    Events: `ready` once on open; `message` (data `{"ref": threadId}`) when the business writes in one \
                    of your threads. Ids only: refetch the thread. A keep-alive comment every 25 s; the stream ends \
                    after 10 minutes and EventSource reconnects.""")
    SseEmitter stream(CurrentUser user) {
        return streams.open(StudioLive.customerKey(user.userId()));
    }
}
