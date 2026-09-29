package ca.northline.developer.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code developer.api_key_revoked} — the key stops working at once (the partner gateway evicts its cache). Topic
 * {@code developer.api_key}, key = api key id. Schema {@code events/developer.api_key_revoked.v1.schema.json}.
 */
@Externalized("developer.api_key::#{aggregateId()}")
public record ApiKeyRevoked(String eventId, Instant occurredAt, String aggregateId, String actorId, String merchantId)
        implements DomainEvent {}
