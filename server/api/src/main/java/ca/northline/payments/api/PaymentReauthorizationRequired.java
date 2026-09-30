package ca.northline.payments.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;
import org.springframework.modulith.events.Externalized;

/**
 * {@code payment.reauthorization_required} — the card hold of a job or order line is about to lapse (Stripe keeps a
 * manual-capture authorization 7 days) and Northline could not renew it off-session (declined, or the bank wants the
 * customer to authenticate). The notification worker asks the customer to confirm the payment again before
 * {@code lapsesAt}. Kafka topic {@code payments.payment}, key = escrow id.
 */
@Externalized("payments.payment::#{aggregateId()}")
public record PaymentReauthorizationRequired(
        String eventId,
        Instant occurredAt,
        String aggregateId,
        String merchantId,
        String customerId,
        String refType,
        String refId,
        Instant lapsesAt)
        implements DomainEvent {}
