package ca.northline.studio.application;

import ca.northline.shared.Ids;
import ca.northline.studio.application.StudioLive.Signal;
import ca.northline.studio.application.StudioLive.Topic;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * S-113 kitchen display (KDS) freshness: a kitchen's screen is only as fresh as the live bus that wakes it (S-68). The
 * bus can break silently — Valkey pub/sub drops, the listener container stalls — and the open streams keep sending
 * keep-alives, so the Studio never falls back to polling. Every {@code northline.live.probe-interval} (30 s) each api
 * replica sends a signal to itself through {@link StudioLive} and times its return:
 * {@code northline.studio.live.probe{outcome=delivered|lost}} — a probe that has not come back by the next one is
 * {@code lost}. The KDS freshness SLO counts probes back within 2 s (docs/runbooks/alerting.md).
 *
 * <p>The probe's channel is {@code probe.<id>} — never a merchant id (ULIDs have no dot), so no browser sees it.
 */
@Component
public class LiveBusProbe {

    static final String PROBE = "northline.studio.live.probe";

    private final StudioLive live;
    private final MeterRegistry meters;
    private final LongSupplier nanos;
    private final String channel = "probe." + Ids.next();
    private final AtomicReference<@Nullable Sent> pending = new AtomicReference<>();
    private final StudioLive.Subscription subscription;

    @Autowired
    LiveBusProbe(StudioLive live, MeterRegistry meters) {
        this(live, meters, System::nanoTime);
    }

    LiveBusProbe(StudioLive live, MeterRegistry meters, LongSupplier nanos) {
        this.live = live;
        this.meters = meters;
        this.nanos = nanos;
        this.subscription = live.subscribe(channel, this::received);
    }

    /** Counts the previous probe as lost when it never came back, then sends the next one. */
    public void probe() {
        var previous = pending.getAndSet(null);
        if (previous != null) {
            record("lost", previous);
        }
        var sent = new Sent(Ids.next(), nanos.getAsLong());
        pending.set(sent);
        live.signal(channel, new Signal(Topic.KITCHEN, sent.nonce()));
    }

    private void received(Signal signal) {
        var sent = pending.get();
        if (sent != null && sent.nonce().equals(signal.ref()) && pending.compareAndSet(sent, null)) {
            record("delivered", sent);
        }
    }

    private void record(String outcome, Sent sent) {
        Timer.builder(PROBE)
                .tag("outcome", outcome)
                .register(meters)
                .record(Duration.ofNanos(Math.max(0, nanos.getAsLong() - sent.at())));
    }

    @PreDestroy
    void stop() {
        subscription.close();
    }

    private record Sent(String nonce, long at) {}
}
