package ca.northline.fulfilment.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.Externalized;

/**
 * {@code run.planned} — a courier run was planned (S-86): a pooled window's orders (or part of them) or one direct
 * order, its stops ordered. Kafka topic {@code fulfilment.run}, key = run id. Ids only.
 *
 * @param kind {@code pooled} | {@code direct}
 * @param heuristic how the stops were ordered ({@code nearest-neighbour-v1})
 */
@Externalized("fulfilment.run::#{aggregateId()}")
public record RunPlanned(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String market,
        String kind,
        @Nullable String windowId,
        List<String> orderIds,
        int stops,
        String heuristic)
        implements DomainEvent {

    public RunPlanned {
        orderIds = List.copyOf(orderIds);
    }
}
