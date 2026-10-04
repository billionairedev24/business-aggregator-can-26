package ca.northline.merchants.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;

/**
 * A business's licence for an age-restriction class came into force (approved) or ended (expired, or replaced with none
 * in force). The catalogue and food modules publish or hide the business's restricted listings of that class.
 * In-process only; ids and codes.
 *
 * @param aggregateId the business
 * @param ageClass {@code alcohol | tobacco | cannabis}
 * @param licensed the business may sell the class now
 */
public record RestrictedLicenceChanged(
        String eventId, Instant occurredAt, String aggregateId, String ageClass, boolean licensed)
        implements DomainEvent {}
