package ca.northline.orders.web;

import ca.northline.orders.application.TrackOrder;
import ca.northline.orders.application.TrackOrder.OrderTracking;
import ca.northline.orders.application.TrackingBus;
import ca.northline.shared.security.CurrentUser;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
 *                                          (EventSource reconnects)
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/me/orders")
class ConsumerOrderController {

    static final Duration STREAM = Duration.ofMinutes(30);
    static final Duration HEARTBEAT = Duration.ofSeconds(25);

    private final TrackOrder track;
    private final TrackingBus bus;
    private final ScheduledExecutorService heartbeats =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());

    ConsumerOrderController(TrackOrder track, TrackingBus bus) {
        this.track = track;
        this.bus = bus;
    }

    @GetMapping("/{orderId}")
    ResponseEntity<OrderTracking> order(CurrentUser user, @PathVariable String orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(track.view(user.userId(), orderId));
    }

    @GetMapping(path = "/{orderId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(CurrentUser user, @PathVariable String orderId) {
        var first = track.view(user.userId(), orderId); // 404 before the stream opens
        var emitter = new SseEmitter(STREAM.toMillis());
        Runnable push = () -> send(emitter, user.userId(), orderId);
        var subscription = bus.subscribe(orderId, push);
        var beat = heartbeats.scheduleAtFixedRate(
                () -> {
                    try {
                        emitter.send(SseEmitter.event().comment("keep-alive"));
                    } catch (IOException | IllegalStateException e) {
                        emitter.complete();
                    }
                },
                HEARTBEAT.toSeconds(),
                HEARTBEAT.toSeconds(),
                TimeUnit.SECONDS);
        Runnable close = () -> {
            subscription.close();
            beat.cancel(false);
        };
        emitter.onCompletion(close);
        emitter.onTimeout(close);
        emitter.onError(_ -> close.run());
        try {
            emitter.send(SseEmitter.event().name("order").data(first, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    private void send(SseEmitter emitter, String userId, String orderId) {
        try {
            emitter.send(SseEmitter.event().name("order").data(track.view(userId, orderId), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("Tracking stream of {} closed: {}", orderId, e.getMessage());
            emitter.complete();
        }
    }
}
