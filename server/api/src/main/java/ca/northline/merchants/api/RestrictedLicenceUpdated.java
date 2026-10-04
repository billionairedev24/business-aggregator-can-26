package ca.northline.merchants.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * A licence for an age-restriction class was decided, is about to expire or expired; the messaging module emails the
 * business's owners (template {@code restricted-licence}). In-process only; the note is the reviewer's words to the
 * business, no personal data.
 *
 * @param aggregateId the licence id
 * @param what {@code approved | rejected | expiring | expired}
 * @param ageClass {@code alcohol | tobacco | cannabis}
 * @param rejectReason for {@code rejected}
 */
public record RestrictedLicenceUpdated(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String merchantId,
        String what,
        String ageClass,
        LocalDate expiresOn,
        @Nullable String rejectReason,
        @Nullable String note)
        implements DomainEvent {}
