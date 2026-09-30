package ca.northline.payments.application;

import ca.northline.payments.api.IdentitySessionUpdated;
import ca.northline.payments.application.StripeEventStore.State;
import ca.northline.payments.domain.Payout;
import ca.northline.shared.Ids;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies stored Stripe events, each in its own transaction under a row lock (the listener and the retry job never
 * apply one twice). Handlers are written for any order of arrival: payouts and cases never leave a final state, and
 * disputes / accounts ignore an event older than the last one applied. Unknown types are stored as {@code ignored}
 * and logged. A failing event is marked {@code failed} and retried by the payments job (up to
 * {@link #MAX_ATTEMPTS}).
 */
@Slf4j
@Service
class StripeEventProcessor {

    static final int MAX_ATTEMPTS = 10;

    /** Processed events are kept this long for support, then purged. */
    static final Duration RETENTION = Duration.ofDays(30);

    private final StripeEventStore store;
    private final StripeEventVerifier verifier;
    private final PayoutService payouts;
    private final RefundCaseService cases;
    private final ConnectedAccountService accounts;
    private final StripeChargeSync charges;
    private final ApplicationEventPublisher events;
    private final BankAccountService bankAccounts;
    private final TransactionTemplate transactions;
    private final Clock clock;

    StripeEventProcessor(
            StripeEventStore store,
            StripeEventVerifier verifier,
            PayoutService payouts,
            RefundCaseService cases,
            ConnectedAccountService accounts,
            StripeChargeSync charges,
            ApplicationEventPublisher events,
            BankAccountService bankAccounts,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.store = store;
        this.verifier = verifier;
        this.payouts = payouts;
        this.cases = cases;
        this.accounts = accounts;
        this.charges = charges;
        this.events = events;
        this.bankAccounts = bankAccounts;
        this.clock = clock;
        // each event in its own transaction, also when called from the listener's
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Processes one event; a failure is recorded on the event instead of propagating. */
    public boolean processOrRecordFailure(String eventId) {
        try {
            return Boolean.TRUE.equals(transactions.execute(_ -> process(eventId)));
        } catch (RuntimeException e) {
            log.warn("Stripe event {} failed: {}", eventId, e.toString());
            transactions.executeWithoutResult(_ -> store.failed(eventId, String.valueOf(e), clock.instant()));
            return false;
        }
    }

    /** The retry job: pending and failed events, oldest first; then the purge. Returns how many were processed. */
    public int processPending() {
        int n = 0;
        for (var id : Objects.requireNonNull(transactions.execute(_ -> store.pending(MAX_ATTEMPTS, 200)))) {
            if (processOrRecordFailure(id)) {
                n++;
            }
        }
        transactions.executeWithoutResult(_ -> store.purge(clock.instant().minus(RETENTION)));
        return n;
    }

    private boolean process(String eventId) {
        var stored = store.lock(eventId).orElse(null);
        if (stored == null || stored.state() == State.PROCESSED || stored.state() == State.IGNORED) {
            return false;
        }
        var event = stored.event();
        var now = clock.instant();
        if (event.livemode() != verifier.livemode()) {
            store.finish(eventId, State.IGNORED, now, "livemode mismatch");
            return false;
        }
        var handled = apply(event);
        if (!handled) {
            log.info("Stripe event {} ({}) ignored", event.id(), event.type());
        }
        store.finish(eventId, handled ? State.PROCESSED : State.IGNORED, now, null);
        return handled;
    }

    /** Dispatch by type; false = not an event Northline acts on. */
    private boolean apply(StripeEvent event) {
        var o = event.object();
        return switch (event.type()) {
            case "payout.paid" -> payouts.stripePayout(event, Payout.State.PAID);
            case "payout.failed" -> payouts.stripePayout(event, Payout.State.FAILED);
            case "payout.canceled" -> payouts.stripePayout(event, Payout.State.CANCELED);
            case "charge.dispute.created",
                    "charge.dispute.updated",
                    "charge.dispute.closed",
                    "charge.dispute.funds_withdrawn",
                    "charge.dispute.funds_reinstated" -> cases.chargeback(event);
            case "account.updated" -> accounts.stripeAccountUpdated(event);
            case "payment_intent.amount_capturable_updated",
                    "payment_intent.succeeded",
                    "payment_intent.payment_failed",
                    "payment_intent.canceled" -> charges.paymentIntent(event);
            case "charge.refunded" -> charges.chargeRefunded(o);
            case "refund.created", "refund.updated", "refund.failed" -> charges.refund(o);
            case "transfer.reversed", "transfer.updated" -> charges.transferReversed(o);
            case "identity.verification_session.created",
                    "identity.verification_session.processing",
                    "identity.verification_session.requires_input",
                    "identity.verification_session.verified",
                    "identity.verification_session.canceled" -> identitySession(event);
            case "financial_connections.account.disconnected", "financial_connections.account.deactivated" ->
                bankAccounts.connectionEnded(event);
            default -> false;
        };
    }

    /**
     * Stripe Identity (S-22): the merchants module owns the owners' checks; payments hands the verified, de-duplicated
     * update over in the same transaction (outbox), without the session's personal data.
     */
    private boolean identitySession(StripeEvent event) {
        var o = event.object();
        var id = o.id();
        var status = o.text("status");
        if (id == null || status == null || event.endpoint() != StripeEvent.Endpoint.PLATFORM) {
            return false;
        }
        events.publishEvent(new IdentitySessionUpdated(
                Ids.next(),
                event.created(),
                id,
                status,
                o.text("last_error", "code"),
                o.text("metadata", "northline_merchant_id")));
        return true;
    }
}
