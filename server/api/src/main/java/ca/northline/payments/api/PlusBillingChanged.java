package ca.northline.payments.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;

/**
 * S-162: a Plus membership's billing changed without the member asking — a renewal was paid, refused, waits for the
 * bank, lapsed, or a period was refunded (replaces S-152's {@code PlusSubscriptionUpdated} from Stripe Billing's
 * webhooks). The loyalty module updates the membership; an event older than the last one applied changes nothing.
 * In-process only; ids, codes and dates — no personal data.
 *
 * @param aggregateId the billing id ({@code payments.plus_schedules.id})
 * @param status {@code incomplete | trialing | active | past_due | canceled}
 */
public record PlusBillingChanged(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String householdId,
        String plan,
        String status,
        Instant currentPeriodEnd,
        boolean cancelAtPeriodEnd)
        implements DomainEvent {}
