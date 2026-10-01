package ca.northline.studio.web;

import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.shared.security.RequiresMerchant;
import ca.northline.studio.application.LiveProperties;
import ca.northline.studio.application.StudioLive;
import ca.northline.studio.application.StudioLive.Signal;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;
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
@Slf4j
@RestController
class LiveController {

    static final long RECONNECT_MS = 3_000;

    private final StudioLive live;
    private final LiveProperties props;
    private final Clock clock;
    /** One timer for the keep-alives; each beat and each push runs on its own virtual thread. */
    private final SimpleAsyncTaskScheduler scheduler = new SimpleAsyncTaskScheduler();

    LiveController(StudioLive live, LiveProperties props, Clock clock) {
        this.live = live;
        this.props = props;
        this.clock = clock;
        scheduler.setVirtualThreads(true);
        scheduler.setThreadNamePrefix("studio-live-");
    }

    @PreDestroy
    void stop() {
        scheduler.close();
    }

    @GetMapping(path = "/api/v1/merchants/{merchantId}/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequiresMerchant(VIEW)
    @Operation(summary = "The Studio's live stream (server-sent events)", description = """
                    Events: `ready` once on open; `message` (data `{"ref": threadId}`) when a message is added to one \
                    of the business's threads; `kitchen` (`{"ref": orderId|null}`) when a food order arrives or moves \
                    or the kitchen pauses/resumes; `orders` (`{"ref": orderId}`) when a goods order arrives or is \
                    packed. Ids only: refetch the screen's data. A keep-alive comment every 25 s; the stream ends \
                    after 10 minutes and EventSource reconnects.""")
    SseEmitter stream(@PathVariable String merchantId) {
        var emitter = new SseEmitter(props.stream().toMillis());
        // pushes leave the pub/sub listener's thread at once: a slow browser never holds up another business's signal
        var subscription = live.subscribe(merchantId, signal -> scheduler.execute(() -> push(emitter, signal)));
        var beat = scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        emitter.send(SseEmitter.event().comment("keep-alive"));
                    } catch (IOException | IllegalStateException e) {
                        emitter.complete();
                    }
                },
                clock.instant().plus(props.heartbeat()),
                props.heartbeat());
        Runnable close = () -> {
            subscription.close();
            beat.cancel(false);
        };
        emitter.onCompletion(close);
        emitter.onTimeout(close);
        emitter.onError(_ -> close.run());
        try {
            emitter.send(
                    SseEmitter.event().name("ready").reconnectTime(RECONNECT_MS).data(new Event(null), JSON));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    private static void push(SseEmitter emitter, Signal signal) {
        try {
            emitter.send(SseEmitter.event().name(signal.topic().code()).data(new Event(signal.ref()), JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("Studio live stream closed: {}", e.getMessage());
            emitter.complete();
        }
    }

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    /** The data of every event: the thread or order id, when there is one. */
    record Event(@Nullable String ref) {}
}
