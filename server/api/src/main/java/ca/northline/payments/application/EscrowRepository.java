package ca.northline.payments.application;

import ca.northline.payments.application.PaymentGateway.IntentStatus;
import ca.northline.payments.domain.Escrow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port for escrows, the Stripe PaymentIntent mirror behind them, their transfers and Stripe customers. */
public interface EscrowRepository {

    /** A row of {@code payments.payment_intents}. */
    record Intent(
            String id,
            String stripePaymentIntent,
            IntentStatus state,
            @Nullable String customerId,
            long amountCents,
            @Nullable String stripeCustomer,
            @Nullable String paymentMethod,
            @Nullable String charge,
            @Nullable String transferGroup,
            @Nullable String refType,
            @Nullable String refId,
            @Nullable String merchantId,
            @Nullable Instant authorizedAt,
            @Nullable Instant captureBefore,
            int reauthorizations,
            @Nullable Instant reauthFailedAt) {}

    /** What is recorded about a PaymentIntent (insert, or update of the row with the same {@code pi_…}). */
    record IntentRecord(
            String stripePaymentIntent,
            IntentStatus state,
            String customerId,
            long amountCents,
            @Nullable String stripeCustomer,
            @Nullable String paymentMethod,
            @Nullable String charge,
            @Nullable String transferGroup,
            @Nullable String refType,
            @Nullable String refId,
            @Nullable String merchantId,
            @Nullable Instant authorizedAt,
            @Nullable Instant captureBefore,
            int reauthorizations) {}

    /** A transfer to a connected account ({@code payments.transfers}). */
    record TransferRecord(String id, String stripeTransfer, long netCents, long reversedCents) {}

    Optional<Escrow> findById(String id);

    Optional<Escrow> findByRef(String refType, String refId);

    /** The escrow a PaymentIntent row currently backs. */
    Optional<Escrow> findByPaymentIntentId(String paymentIntentId);

    /** Held escrows whose release time has passed, oldest first. */
    List<Escrow> releasable(Instant now, int limit);

    /** Escrows still only authorized whose hold lapses before {@code before} (renewal candidates). */
    List<Escrow> authorizationsLapsingBefore(Instant before, int limit);

    /** Inserts or updates the PaymentIntent row; returns its id. */
    String recordPaymentIntent(IntentRecord intent);

    void markPaymentIntent(String paymentIntentId, IntentStatus state);

    /** Stripe says it is authorized ({@code amount_capturable_updated}). */
    void markAuthorized(String paymentIntentId, Instant at);

    /** Captured: the charge transfers will draw on. */
    void recordCapture(String paymentIntentId, @Nullable String stripeCharge);

    Optional<Intent> intent(String paymentIntentId);

    Optional<Intent> intentByStripeId(String stripePaymentIntent);

    /** The current (not replaced) PaymentIntent of a reference, locked for the rest of the transaction. */
    Optional<Intent> currentIntentForUpdate(String refType, String refId);

    /** The old hold was canceled and replaced by {@code newPaymentIntentId}. */
    void replacePaymentIntent(String oldPaymentIntentId, String newPaymentIntentId);

    void reauthorizationFailed(String paymentIntentId, Instant at);

    Optional<String> stripeCustomer(String customerId);

    void saveStripeCustomer(String customerId, String stripeCustomer);

    void insert(Escrow escrow);

    void update(Escrow escrow);

    void recordTransfer(
            String escrowId,
            String stripeTransfer,
            String transferGroup,
            long grossCents,
            long feeCents,
            long netCents,
            Instant at);

    Optional<TransferRecord> transferOf(String escrowId);

    void addReversal(String transferId, long cents);

    /** Stripe's cumulative {@code amount_reversed} of a transfer; false when the transfer isn't Northline's. */
    boolean syncReversed(String stripeTransfer, long amountReversedCents);
}
