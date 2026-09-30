package ca.northline.worker.events;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code events.processed_events (consumer, event_id)} — one table, shared by the api and the worker, whose primary key
 * makes "first one wins" atomic across threads, instances and apps:
 *
 * <ul>
 *   <li>{@code consumer} = the worker's Kafka consumer group, {@code event_id} = the event id: the whole event was
 *       handled by that group ({@link EventProcessing}, same transaction as the handler's own writes);
 *   <li>{@code consumer} = a delivery channel ({@code email}, {@code sms}, {@code push}), {@code event_id} =
 *       {@code <eventId>:<userId>}: that person was notified about that event — claimed before sending, by the api
 *       (S-13 {@code Mailer}) or the worker (S-27), so the two can never both send;
 *   <li>{@code consumer} = {@code dlq-replay}, {@code event_id} = {@code <dlq topic>:<partition>:<offset>}: that DLQ
 *       record was replayed ({@link DlqReplay}).
 * </ul>
 */
@RequiredArgsConstructor
public class ProcessedEvents {

    private final JdbcClient jdbc;
    private final Clock clock;

    /** Records {@code (consumer, key)}; false when it already was (a duplicate). In the caller's transaction. */
    public boolean claim(String consumer, String key) {
        return jdbc.sql("""
                        insert into events.processed_events (consumer, event_id, processed_at) values (:c, :k, :at)
                        on conflict do nothing""")
                        .param("c", consumer)
                        .param("k", key)
                        .param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                        .update()
                == 1;
    }

    /** Takes a claim back (the send it guarded didn't happen). In the caller's transaction. */
    public void release(String consumer, String key) {
        jdbc.sql("delete from events.processed_events where consumer = :c and event_id = :k")
                .param("c", consumer)
                .param("k", key)
                .update();
    }

    public boolean contains(String consumer, String key) {
        return jdbc.sql("select count(*) from events.processed_events where consumer = :c and event_id = :k")
                        .param("c", consumer)
                        .param("k", key)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    /** Deletes claims older than {@code retention}; returns how many. */
    public int purgeOlderThan(Duration retention) {
        return jdbc.sql("delete from events.processed_events where processed_at < :before")
                .param("before", OffsetDateTime.ofInstant(clock.instant().minus(retention), ZoneOffset.UTC))
                .update();
    }
}
