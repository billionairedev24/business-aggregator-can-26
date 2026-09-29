package ca.northline.worker;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class ProcessedEvents {
    private final JdbcClient jdbc;

    boolean markIfNew(String consumer, String eventId) {
        return jdbc.sql("insert into events.processed_events(consumer, event_id) values (?, ?) on conflict do nothing")
                        .params(consumer, eventId)
                        .update()
                == 1;
    }
}
