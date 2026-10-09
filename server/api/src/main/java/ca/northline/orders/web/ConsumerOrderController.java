package ca.northline.orders.web;

import ca.northline.orders.application.ConfirmDelivery;
import ca.northline.orders.application.TrackOrder;
import ca.northline.orders.application.TrackOrder.OrderTracking;
import ca.northline.orders.application.TrackOrder.ProofPhoto;
import ca.northline.shared.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The customer's order and its live tracking (S-52):
 *
 * <pre>
 * GET /api/v1/me/orders/{orderId}          the order, its shops and the timeline (404 unless it is the caller's)
 * GET /api/v1/me/orders/{orderId}/events   text/event-stream: event "order" with the same JSON now and on every change;
 *                                          a comment every 25 s keeps proxies from closing it; ends after 30 minutes
 *                                          (EventSource reconnects); S-88: also on every move of the courier
 *                                          carrying it (OrderStreams)
 * POST /api/v1/me/orders/{orderId}/confirm  the customer received it (S-78): goods escrow releases at once; 409
 *                                          not_delivered before the courier's pickup; repeating it changes nothing
 * GET /api/v1/me/orders/{orderId}/proof-photo  {url, expiresAt}: the courier's door photo as a 5-minute signed URL;
 *                                          403 someone else's order, 404 none to show (mobile gaps part 1)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/me/orders")
@RequiredArgsConstructor
class ConsumerOrderController {

    private final TrackOrder track;
    private final ConfirmDelivery confirmDelivery;
    private final OrderStreams streams;

    @GetMapping("/{orderId}")
    ResponseEntity<OrderTracking> order(CurrentUser user, @PathVariable String orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(track.view(user.userId(), orderId));
    }

    @PostMapping("/{orderId}/confirm")
    ResponseEntity<OrderTracking> confirm(CurrentUser user, @PathVariable String orderId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(confirmDelivery.confirm(user.userId(), orderId));
    }

    @GetMapping("/{orderId}/proof-photo")
    ResponseEntity<ProofPhoto> proofPhoto(CurrentUser user, @PathVariable String orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(track.proofPhoto(user.userId(), orderId));
    }

    @GetMapping(path = "/{orderId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(CurrentUser user, @PathVariable String orderId) {
        return streams.open(orderId, "order", () -> track.view(user.userId(), orderId));
    }
}
