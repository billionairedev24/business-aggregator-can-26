package ca.northline.payments.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.Externalized;

/**
 * {@code plus_payment.updated} (S-162) — a Northline Plus payment the member should hear about: {@code paid} (the
 * receipt), {@code action_required} (the bank wants them to confirm: 3-D Secure on the web or in the app),
 * {@code declined} (tried again at {@code retryAt}), {@code lapsed} (no retry left: the membership ended) or
 * {@code refunded}. The notification worker emails and pushes the payer. Kafka topic {@code payments.payment} (no new
 * topic), key = the period's id. Ids, amounts and dates only.
 *
 * @param aggregateId {@code payments.plus_periods.id}
 * @param customerId the payer ({@code identity.users.id}); the worker looks up the contact details
 * @param amountCents the plan's price before tax
 * @param code the decline code for {@code declined}, else null
 */
@Externalized("payments.payment::#{aggregateId()}")
public record PlusPaymentUpdated(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String customerId,
        String outcome,
        String plan,
        long amountCents,
        long taxCents,
        Instant periodStart,
        Instant periodEnd,
        @Nullable Instant retryAt,
        @Nullable String code)
        implements DomainEvent {}
