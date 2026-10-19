package ca.northline.merchants.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;

/**
 * Stage 1 of the two-person review (2026-10-18): a Northline agent recommended a decision on a submitted application;
 * another agent decides it ({@link ApplicationDecided}). In-process only, like {@link ApplicationDecided}. Ids and codes
 * only: the reviewer's note stays in the merchants schema and the audit log.
 *
 * @param aggregateId the merchant id
 * @param actorId the reviewer
 * @param reviewId {@code merchants.application_reviews.id}
 * @param recommendation {@code approve | reject | request_info}
 */
public record ApplicationReviewed(
        String eventId, Instant occurredAt, String aggregateId, String actorId, String reviewId, String recommendation)
        implements DomainEvent {}
