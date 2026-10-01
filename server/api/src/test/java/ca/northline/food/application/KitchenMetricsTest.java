package ca.northline.food.application;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.food.domain.KitchenStage;
import ca.northline.food.domain.KitchenTicket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** S-111: kitchen display latency from the ticket's own timestamps, tagged without ids. */
class KitchenMetricsTest {

    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final KitchenMetrics metrics = new KitchenMetrics(meters);
    final Instant t0 = Instant.parse("2026-10-01T18:00:00Z");

    @Test
    void recordsPromisedPrepAndHandoffWithoutIds() {
        var ticket = KitchenTicket.builder()
                .orderId("01J9ZD3V0000000000000ORD01")
                .merchantId("01J9ZD3V00000000000000PDB1")
                .fulfilmentMode("pickup")
                .orderOpen(true)
                .stage(KitchenStage.NEW)
                .build();
        ticket.accept("cook", t0, 12);
        metrics.accepted(ticket);
        ticket.ready("cook", t0.plusSeconds(15 * 60));
        metrics.ready(ticket);
        ticket.handOff("cook", t0.plusSeconds(17 * 60));
        metrics.handedOff(ticket);

        assertThat(meters.get(KitchenMetrics.PROMISED).summary().totalAmount()).isEqualTo(12);
        var prep = meters.get(KitchenMetrics.PREP).tag("late", "true").timer();
        assertThat(prep.totalTime(TimeUnit.MINUTES)).isEqualTo(15);
        assertThat(meters.get(KitchenMetrics.HANDOFF_WAIT)
                        .tag("mode", "pickup")
                        .timer()
                        .totalTime(TimeUnit.MINUTES))
                .isEqualTo(2);
        assertThat(meters.getMeters())
                .allSatisfy(m -> assertThat(m.getId().getTags())
                        .noneSatisfy(t -> assertThat(t.getValue()).startsWith("01J9")));
    }
}
