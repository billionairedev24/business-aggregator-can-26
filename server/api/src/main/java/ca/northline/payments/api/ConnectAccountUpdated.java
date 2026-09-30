package ca.northline.payments.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Stripe reported a change on a merchant's Connect account ({@code account.updated}): capabilities, requirements, the
 * payout destination. In-process only (merchants links the account to the business and the compliance screen reads
 * the requirements live); not published to Kafka.
 */
public record ConnectAccountUpdated(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String stripeAccount,
        boolean chargesEnabled,
        boolean payoutsEnabled,
        int requirementsDue,
        int requirementsPastDue,
        @Nullable String disabledReason)
        implements DomainEvent {}
