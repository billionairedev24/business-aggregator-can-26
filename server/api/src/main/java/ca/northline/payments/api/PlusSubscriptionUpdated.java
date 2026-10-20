package ca.northline.payments.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * S-152: a Plus subscription changed at the billing provider (a verified, de-duplicated Stripe webhook:
 * {@code customer.subscription.*}), or a local renewal. The loyalty module updates the membership; an event older than
 * the last one applied is ignored there. In-process only; ids, codes and dates — no personal data.
 *
 * @param householdId the subscription's {@code northline_household} metadata; null when it carries none
 * @param plan {@code monthly | annual}, null when the subscription doesn't say
 * @param status {@code incomplete | trialing | active | past_due | canceled}
 */
public record PlusSubscriptionUpdated(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        @Nullable String householdId,
        @Nullable String plan,
        String status,
        @Nullable Instant currentPeriodEnd,
        boolean cancelAtPeriodEnd)
        implements DomainEvent {}
