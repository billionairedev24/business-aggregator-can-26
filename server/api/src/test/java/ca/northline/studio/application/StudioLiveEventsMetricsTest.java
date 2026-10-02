package ca.northline.studio.application;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.orders.api.OrderPlaced;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** S-113 KDS ticket delivery: a food order's time from placed to the kitchen's live bus; goods orders don't count. */
class StudioLiveEventsMetricsTest {

    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final Instant placed = Instant.parse("2026-10-02T17:00:00Z");
    final LiveBusProbeTest.Bus bus = new LiveBusProbeTest.Bus();
    final StudioLiveEvents events =
            new StudioLiveEvents(bus, meters, Clock.fixed(placed.plusMillis(1_200), ZoneOffset.UTC));

    OrderPlaced order(String type) {
        return new OrderPlaced("e1", placed, "o1", "m1", "c1", "NL-1", type, "pickup", null, 1_000, 50, List.of());
    }

    @Test
    void timesAFoodOrderFromPlacedToTheKitchensBus() {
        events.on(order("food"));
        events.on(order("goods"));

        var delivery = meters.get(StudioLiveEvents.TICKET_DELIVERY).timer();
        assertThat(delivery.count()).isEqualTo(1);
        assertThat(delivery.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1_200);
        assertThat(bus.channels).containsExactly("m1", "m1");
    }
}
