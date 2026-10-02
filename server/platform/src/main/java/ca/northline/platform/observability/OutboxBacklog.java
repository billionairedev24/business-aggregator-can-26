package ca.northline.platform.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S-113: the transactional outbox's backlog — Spring Modulith's event publication registry
 * ({@code <schema>.event_publication}, S-25) in the api ({@code events}) and in northline-auth ({@code auth}). A
 * publication stays incomplete until every listener, including the Kafka externalizer, has handled it; a growing
 * backlog means events are not leaving (Kafka down, a listener failing on every retry):
 *
 * <ul>
 *   <li>{@code northline.events.outbox.pending} — incomplete publications;
 *   <li>{@code northline.events.outbox.oldest_age} — seconds since the oldest incomplete one was published (0 when
 *       none; Prometheus {@code northline_events_outbox_oldest_age_seconds}).
 * </ul>
 *
 * One query (on the registry's partial index of incomplete rows) serves both gauges and is reused for
 * {@link #CACHE_FOR}, however often the registry is read (OTLP export every 30 s, a Prometheus scrape). A failing query
 * leaves the gauges empty (NaN) rather than failing the export.
 */
@Slf4j
public final class OutboxBacklog implements MeterBinder {

    static final String PENDING = "northline.events.outbox.pending";
    static final String OLDEST_AGE = "northline.events.outbox.oldest_age";
    static final Duration CACHE_FOR = Duration.ofSeconds(15);
    private static final Pattern SCHEMA = Pattern.compile("[a-z_][a-z0-9_]*");

    /** What the registry holds now. */
    public record Snapshot(long pending, double oldestAgeSeconds) {}

    private final Supplier<Snapshot> query;
    private final Clock clock;
    private @Nullable Snapshot last;
    private Instant readAt = Instant.MIN;

    OutboxBacklog(Supplier<Snapshot> query, Clock clock) {
        this.query = query;
        this.clock = clock;
    }

    /** Over {@code <schema>.event_publication} (the value of {@code spring.modulith.events.jdbc.schema}). */
    public static OutboxBacklog of(JdbcTemplate jdbc, String schema) {
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("Not a schema name: " + schema);
        }
        var sql = "select count(*), coalesce(extract(epoch from now() - min(publication_date)), 0) from " + schema
                + ".event_publication where completion_date is null";
        return new OutboxBacklog(
                () -> jdbc.queryForObject(sql, (rs, _) -> new Snapshot(rs.getLong(1), rs.getDouble(2))),
                Clock.systemUTC());
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(PENDING, this, b -> b.read(Snapshot::pending))
                .description("Event publications not yet completed by every listener (the outbox backlog)")
                .register(registry);
        Gauge.builder(OLDEST_AGE, this, b -> b.read(Snapshot::oldestAgeSeconds))
                .description("Age of the oldest incomplete event publication")
                .baseUnit("seconds")
                .register(registry);
    }

    private synchronized double read(ToDoubleFunction<Snapshot> value) {
        var now = clock.instant();
        if (last == null || !now.isBefore(readAt.plus(CACHE_FOR))) {
            readAt = now;
            try {
                last = query.get();
            } catch (RuntimeException e) {
                last = null;
                log.warn("Outbox backlog unreadable: {}", e.getMessage());
            }
        }
        return last == null ? Double.NaN : value.applyAsDouble(last);
    }
}
