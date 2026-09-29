package ca.northline.payments.application;

import ca.northline.payments.domain.Escrow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Outbound port for escrows and the Stripe PaymentIntent mirror behind them. */
public interface EscrowRepository {

    Optional<Escrow> findById(String id);

    Optional<Escrow> findByRef(String refType, String refId);

    /** Held escrows whose release time has passed, oldest first. */
    List<Escrow> releasable(Instant now, int limit);

    /** Records the authorized PaymentIntent; returns its id. */
    String recordPaymentIntent(String stripePaymentIntent, String customerId, long amountCents);

    void markPaymentIntent(String paymentIntentId, String state);

    /** Stripe PaymentIntent id ({@code pi_…}) of a payment intent row. */
    Optional<String> stripePaymentIntent(String paymentIntentId);

    void insert(Escrow escrow);

    void update(Escrow escrow);

    void recordTransfer(
            String escrowId, String stripeTransfer, long grossCents, long feeCents, long netCents, Instant at);
}
