package ca.northline.studio.application;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.studio.application.StudioLive.Signal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** S-113 KDS freshness: the probe times its own signal through the live bus; one that never returns is lost. */
class LiveBusProbeTest {

    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final AtomicLong nanos = new AtomicLong();

    /** A bus that delivers after {@code delayMs}, or drops everything. */
    static final class Bus implements StudioLive {
        final List<Consumer<Signal>> listeners = new ArrayList<>();
        final List<String> channels = new ArrayList<>();
        boolean drop;
        Runnable beforeDelivery = () -> {};

        @Override
        public void signal(String merchantId, Signal signal) {
            channels.add(merchantId);
            if (!drop) {
                beforeDelivery.run();
                listeners.forEach(l -> l.accept(signal));
            }
        }

        @Override
        public Subscription subscribe(String merchantId, Consumer<Signal> listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }
    }

    @Test
    void timesTheRoundTrip() {
        var bus = new Bus();
        bus.beforeDelivery = () -> nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(40));
        var probe = new LiveBusProbe(bus, meters).nanos(nanos::get);

        probe.probe();

        var delivered =
                meters.get(LiveBusProbe.PROBE).tag("outcome", "delivered").timer();
        assertThat(delivered.count()).isEqualTo(1);
        assertThat(delivered.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(40);
        assertThat(bus.channels.getFirst()).startsWith("probe.").doesNotMatch("[0-9A-Z]{26}");
    }

    @Test
    void aProbeThatNeverReturnsIsLostAtTheNextOne() {
        var bus = new Bus();
        bus.drop = true;
        var probe = new LiveBusProbe(bus, meters).nanos(nanos::get);

        probe.probe();
        assertThat(meters.find(LiveBusProbe.PROBE).timer()).isNull();
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(30));
        probe.probe();

        assertThat(meters.get(LiveBusProbe.PROBE).tag("outcome", "lost").timer().count())
                .isEqualTo(1);
    }

    @Test
    void ignoresSomeoneElsesSignal() {
        var bus = new Bus();
        bus.drop = true;
        var probe = new LiveBusProbe(bus, meters).nanos(nanos::get);
        probe.probe();

        bus.listeners.forEach(l -> l.accept(new Signal(StudioLive.Topic.KITCHEN, "not-the-nonce")));

        assertThat(meters.find(LiveBusProbe.PROBE).timer()).isNull();
    }
}
