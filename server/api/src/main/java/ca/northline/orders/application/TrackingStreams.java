package ca.northline.orders.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Open live order-tracking streams on this replica, as the gauge {@code northline.tracking.streams} (S-91: the console
 * overview's "Tracking" health tile sums it over the replicas). A count only — no order or customer in a label.
 */
@Component
public class TrackingStreams {

    private final AtomicLong open = new AtomicLong();

    TrackingStreams(MeterRegistry meters) {
        Gauge.builder("northline.tracking.streams", open, AtomicLong::get)
                .description("Open order-tracking streams (SSE)")
                .register(meters);
    }

    /** A stream opened; run the returned action when it closes (once is counted, however often it runs). */
    public Runnable opened() {
        open.incrementAndGet();
        var closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                open.decrementAndGet();
            }
        };
    }
}
