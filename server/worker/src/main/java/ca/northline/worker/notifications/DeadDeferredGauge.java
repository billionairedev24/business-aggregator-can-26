package ca.northline.worker.notifications;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Engineering follow-ups (S-115 gap): dead deferred notifications (the table's own dead letters,
 * docs/runbooks/events.md) had no metric, so a provider outage that outlasted the ten attempts went unseen until
 * someone ran the query. {@code northline_notifications_deferred_dead{channel}} is the number of dead rows per
 * channel, refreshed by the every-minute deferred job (one grouped query) — never by the scrape itself — and the
 * {@code NorthlineDeadDeferredNotifications} ticket alert (deploy/observability/alerts) fires on it.
 */
public final class DeadDeferredGauge {

    public static final String METRIC = "northline.notifications.deferred.dead";

    private final Map<Channel, AtomicInteger> dead = new EnumMap<>(Channel.class);

    public DeadDeferredGauge(MeterRegistry meters) {
        for (var channel : Channel.values()) {
            var value = new AtomicInteger();
            dead.put(channel, value);
            Gauge.builder(METRIC, value, AtomicInteger::get)
                    .description(
                            "Deferred notifications given up after their attempts, waiting for a requeue or the purge")
                    .tag("channel", channel.code())
                    .register(meters);
        }
    }

    /** Sets every channel's count (channels missing from {@code counts} have none). */
    public void refresh(Map<Channel, Integer> counts) {
        dead.forEach((channel, value) -> value.set(counts.getOrDefault(channel, 0)));
    }
}
