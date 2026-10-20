package ca.northline.studio.web;

import ca.northline.studio.application.LiveProperties;
import ca.northline.studio.application.StudioLive;
import ca.northline.studio.application.StudioLive.Signal;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * One server-sent-event stream of {@link StudioLive} signals for one key (S-68: a business; S-153: a customer,
 * {@link StudioLive#customerKey}): {@code ready} on open, a keep-alive comment every 25 s, the stream ended after 10
 * minutes so EventSource reconnects through the BFF. Signals carry ids only.
 */
@Slf4j
@Component
class LiveStreams {

    static final long RECONNECT_MS = 3_000;
    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    private final StudioLive live;
    private final LiveProperties props;
    private final Clock clock;
    /** One timer for the keep-alives; each beat and each push runs on its own virtual thread. */
    private final SimpleAsyncTaskScheduler scheduler = new SimpleAsyncTaskScheduler();

    LiveStreams(StudioLive live, LiveProperties props, Clock clock) {
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

    SseEmitter open(String key) {
        var emitter = new SseEmitter(props.stream().toMillis());
        // pushes leave the pub/sub listener's thread at once: a slow browser never holds up another stream's signal
        var subscription = live.subscribe(key, signal -> scheduler.execute(() -> push(emitter, signal)));
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
            log.debug("Live stream closed: {}", e.getMessage());
            emitter.complete();
        }
    }

    /** The data of every event: the thread or order id, when there is one. */
    record Event(@Nullable String ref) {}
}
