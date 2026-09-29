package ca.northline.developer.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code developer.webhook_endpoint_changed} — the webhook worker reloads the endpoint (url, events, secret) before its
 * next delivery. Topic {@code developer.webhook_endpoint}, key = endpoint id. Schema
 * {@code events/developer.webhook_endpoint_changed.v1.schema.json}.
 *
 * @param change {@code created} | {@code secret_rotated} | {@code deleted}
 */
@Externalized("developer.webhook_endpoint::#{aggregateId()}")
public record WebhookEndpointChanged(
        String eventId, Instant occurredAt, String aggregateId, String actorId, String merchantId, String change)
        implements DomainEvent {}
