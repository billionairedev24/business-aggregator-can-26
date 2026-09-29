package ca.northline.worker;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class ProcessedEvents {
    private final JdbcClient jdbc;
    ProcessedEvents(JdbcClient jdbc) { this.jdbc = jdbc; }
    boolean markIfNew(String consumer, String eventId) {
        return jdbc.sql("insert into events.processed_events(consumer, event_id) values (?, ?) on conflict do nothing")
                   .params(consumer, eventId).update() == 1;
    }
}
