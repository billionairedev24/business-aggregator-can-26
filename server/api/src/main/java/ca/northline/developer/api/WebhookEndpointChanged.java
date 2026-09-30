package ca.northline.developer.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code developer.webhook_endpoint_changed} — an endpoint was created, re-keyed, turned back on or deleted (for audit
 * and future consumers; the S-33 webhook worker reads the endpoint's url, events and secrets at delivery time). Topic {@code developer.webhook_endpoint}, key = endpoint id. Schema
 * {@code events/developer.webhook_endpoint_changed.v1.schema.json}.
 *
 * @param change {@code created} | {@code secret_rotated} | {@code enabled} | {@code deleted}
 */
@Externalized("developer.webhook_endpoint::#{aggregateId()}")
public record WebhookEndpointChanged(
        String eventId, Instant occurredAt, String aggregateId, String actorId, String merchantId, String change)
        implements DomainEvent {}
