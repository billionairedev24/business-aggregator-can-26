package ca.northline.food.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;

/**
 * Age-restricted dishes (2026-10-04): the kitchen didn't hand a pickup over at the counter — nobody of age with photo
 * ID came for it. The orders module moves the order to {@code returned} and refunds the restricted dishes. In-process
 * only; ids and codes.
 *
 * @param aggregateId the order
 * @param reason {@code no_id | underage | mismatch | nobody_of_age | intoxicated | other}
 */
public record FoodOrderRefused(
        String eventId, Instant occurredAt, String aggregateId, String merchantId, String actorId, String reason)
        implements DomainEvent {}
