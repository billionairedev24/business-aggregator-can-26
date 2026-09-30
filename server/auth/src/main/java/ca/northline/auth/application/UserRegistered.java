package ca.northline.auth.application;

import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code user.registered} (ARCHITECTURE.md key events) — a Northline account was created: the phone was verified and
 * the second factor confirmed. Published by northline-auth in the registration transaction (outbox:
 * {@code auth.event_publication}), then externalized to Kafka topic {@code identity.user}, key = user id. Ids only, no
 * PII (consumers read names or contacts from {@code identity.users} when they need them). Schema:
 * {@code server/api/src/main/resources/events/identity.user_registered.v1.schema.json}.
 *
 * @param eventId ULID, the consumers' dedupe key
 * @param aggregateId the new {@code identity.users.id}
 */
@Externalized("identity.user::#{aggregateId()}")
public record UserRegistered(String eventId, Instant occurredAt, String aggregateId) {}
