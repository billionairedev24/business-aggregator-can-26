package ca.northline.worker.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Engineering follow-ups (S-115 gap): dead deferred notifications are a gauge per channel, 0 when there are none. */
class DeadDeferredGaugeTest {

    @Test
    void everyChannelReports_andAChannelWithoutDeadRowsGoesBackToZero() {
        var meters = new SimpleMeterRegistry();
        var gauge = new DeadDeferredGauge(meters);

        gauge.refresh(Map.of(Channel.SMS, 3, Channel.PUSH, 1));
        assertThat(meters.get(DeadDeferredGauge.METRIC)
                        .tag("channel", "sms")
                        .gauge()
                        .value())
                .isEqualTo(3);
        assertThat(meters.get(DeadDeferredGauge.METRIC)
                        .tag("channel", "email")
                        .gauge()
                        .value())
                .isZero();

        gauge.refresh(Map.of(Channel.PUSH, 1)); // the SMS were requeued
        assertThat(meters.get(DeadDeferredGauge.METRIC)
                        .tag("channel", "sms")
                        .gauge()
                        .value())
                .isZero();
        assertThat(meters.get(DeadDeferredGauge.METRIC)
                        .tag("channel", "push")
                        .gauge()
                        .value())
                .isEqualTo(1);
    }
}
