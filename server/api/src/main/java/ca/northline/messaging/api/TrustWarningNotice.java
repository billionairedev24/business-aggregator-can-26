package ca.northline.messaging.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * S-93: trust &amp; safety warned a business from a flag in the console ("Off-platform payment attempt detected in
 * messages → warning, then suspension"). Published by trust; the messaging module emails the owners. In-process only.
 *
 * @param aggregateId the flag id
 * @param rule the flag's rule ({@code off_platform_payment}, {@code floor_breach}, …)
 * @param note the staff member's words to the business, or null
 */
public record TrustWarningNotice(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String merchantId,
        String rule,
        @Nullable String note) implements DomainEvent {}
