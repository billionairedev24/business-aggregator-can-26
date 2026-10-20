package ca.northline.studio.web;

import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.shared.security.RequiresMerchant;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * S-68: the Studio's live stream, which replaces most polling of Messages, Orders and the kitchen display.
 *
 * <pre>
 * GET /api/v1/merchants/{merchantId}/live   text/event-stream (any member, VIEW)
 *   event "ready"    once, on open                       data {}
 *   event "message"  a message was added to a thread     data {"ref": "&lt;threadId&gt;"}
 *   event "kitchen"  a food order arrived or moved, or the kitchen paused/resumed   data {"ref": "&lt;orderId&gt;"|null}
 *   event "orders"   a goods order arrived or was packed data {"ref": "&lt;orderId&gt;"}
 *   ": keep-alive"   comment every 25 s; the stream ends after 10 minutes and EventSource reconnects (through the
 *                    BFF, so the session, the token and the membership are checked again)
 * </pre>
 *
 * Signals carry ids only; the browser refetches what it shows with its own permissions, so a role that cannot see a
 * thread learns nothing from its id.
 */
@RestController
@RequiredArgsConstructor
class LiveController {

    private final LiveStreams streams;

    @GetMapping(path = "/api/v1/merchants/{merchantId}/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequiresMerchant(VIEW)
    @Operation(summary = "The Studio's live stream (server-sent events)", description = """
                    Events: `ready` once on open; `message` (data `{"ref": threadId}`) when a message is added to one \
                    of the business's threads; `kitchen` (`{"ref": orderId|null}`) when a food order arrives or moves \
                    or the kitchen pauses/resumes; `orders` (`{"ref": orderId}`) when a goods order arrives or is \
                    packed. Ids only: refetch the screen's data. A keep-alive comment every 25 s; the stream ends \
                    after 10 minutes and EventSource reconnects.""")
    SseEmitter stream(@PathVariable String merchantId) {
        return streams.open(merchantId);
    }
}
