package ca.northline.payments.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code payout_account.changed} — the bank account payouts go to is changing. Published twice: {@code phase=requested}
 * when the owner confirms the new account (payouts pause for 24 h) and {@code phase=effective} when the hold ends. The
 * notification worker emails and texts the owners both times (design: "We email and text you when it's requested and
 * when it takes effect"). No account numbers — only the ids. Kafka topic {@code payments.payout_account}.
 */
@Externalized("payments.payout_account::#{aggregateId()}")
public record PayoutAccountChanged(
        String eventId, Instant occurredAt, String aggregateId, String merchantId, String phase, Instant effectiveAt)
        implements DomainEvent {}
