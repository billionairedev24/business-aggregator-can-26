package ca.northline.orders.web;

import ca.northline.fulfilment.api.CourierLocations;
import ca.northline.orders.application.TrackingBus;
import ca.northline.orders.application.TrackingStreams;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The customer's live tracking streams (S-52 goods, S-88 also food): one {@code text/event-stream} per open page. The
 * event ({@code order} / {@code food}) carries the same JSON as the GET, sent at once and again whenever the order
 * changes (the tracking bus, Valkey pub/sub between replicas) or the courier carrying it moves (fulfilment's courier
 * positions). A comment every 25 s keeps proxies from closing it; it ends after 30 minutes (EventSource reconnects).
 * Each push re-reads the order with the caller's own rights, so a message carries no data. Open streams are counted
 * for the console's health tile ({@link TrackingStreams}, S-91).
 */
@Slf4j
@Component
class OrderStreams {

    static final Duration STREAM = Duration.ofMinutes(30);
    static final Duration HEARTBEAT = Duration.ofSeconds(25);

    private final TrackingBus bus;
    private final CourierLocations couriers;
    private final TrackingStreams gauge;
    /** Spring's scheduler on virtual threads: one timer, each keep-alive on its own virtual thread. */
    private final SimpleAsyncTaskScheduler heartbeats = new SimpleAsyncTaskScheduler();

    OrderStreams(TrackingBus bus, CourierLocations couriers, TrackingStreams gauge) {
        this.bus = bus;
        this.couriers = couriers;
        this.gauge = gauge;
        heartbeats.setVirtualThreads(true);
        heartbeats.setThreadNamePrefix("order-stream-");
    }

    @PreDestroy
    void stop() {
        heartbeats.close();
    }

    /** @param view the order as the caller sees it; called once before the stream opens (its 404 stays a 404) */
    SseEmitter open(String orderId, String event, Supplier<Object> view) {
        var first = view.get();
        var emitter = new SseEmitter(STREAM.toMillis());
        Runnable push = () -> send(emitter, event, orderId, view);
        var changes = bus.subscribe(orderId, push);
        var moves = couriers.subscribe(orderId, push);
        var counted = gauge.opened(); // S-91: the console's "Tracking" health tile
        var beat = heartbeats.scheduleAtFixedRate(
                () -> {
                    try {
                        emitter.send(SseEmitter.event().comment("keep-alive"));
                    } catch (IOException | IllegalStateException e) {
                        emitter.complete();
                    }
                },
                Instant.now().plus(HEARTBEAT),
                HEARTBEAT);
        Runnable close = () -> {
            changes.close();
            moves.close();
            counted.run();
            beat.cancel(false);
        };
        emitter.onCompletion(close);
        emitter.onTimeout(close);
        emitter.onError(_ -> close.run());
        try {
            emitter.send(SseEmitter.event().name(event).data(first, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    private static void send(SseEmitter emitter, String event, String orderId, Supplier<Object> view) {
        try {
            emitter.send(SseEmitter.event().name(event).data(view.get(), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("Tracking stream of {} closed: {}", orderId, e.getMessage());
            emitter.complete();
        } catch (RuntimeException e) { // e.g. the order became invisible: end the stream, the page refetches
            log.debug("Tracking stream of {} ended: {}", orderId, e.getMessage());
            emitter.complete();
        }
    }
}
