package ca.northline.payments.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.Externalized;

/**
 * {@code payout.failed} — Stripe returned a payout (the bank refused it) or it was canceled; the money is back in the
 * merchant's balance. The notification worker asks the owner to check their bank account. Kafka topic
 * {@code payments.payout}, key = payout id.
 *
 * @param outcome {@code failed} | {@code canceled}
 * @param failureCode Stripe's code ({@code account_closed}, {@code no_account}, …), null when canceled
 */
@Externalized("payments.payout::#{aggregateId()}")
public record PayoutFailed(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String merchantId,
        String outcome,
        long amountCents,
        @Nullable String failureCode)
        implements DomainEvent {}
