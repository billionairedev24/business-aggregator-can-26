package ca.northline.hire.application;

import ca.northline.booking.api.BookingMoney;
import ca.northline.booking.api.CustomerQuotes;
import ca.northline.identity.api.PersonDirectory;
import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.api.EscrowLifecycle;
import ca.northline.payments.api.PaymentAuthorizations;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S-153: the balance of an accepted deposit quote is held {@link BalanceProperties#lead()} before the event — off-session
 * on the card behind the deposit (saved to the customer's Stripe Customer at acceptance), as its own escrow
 * ({@code booking_balance}), released with the job. A refusal is retried every {@link BalanceProperties#retryEvery()}
 * up to {@link BalanceProperties#attempts()} times (never after the start); each failure tells the customer (pay it from
 * the booking page) and the provider ({@code booking.balance_failed}); after the last one the balance stays
 * {@code failed} and the provider decides. One transaction per booking: one refusal never stops the others.
 */
@Slf4j
@Service
class BalanceHoldService implements BalanceHolds {

    static final String BALANCE = "booking_balance";
    static final int BATCH = 50;

    private final BookingMoney money;
    private final CustomerQuotes quotes;
    private final PaymentAuthorizations payments;
    private final EscrowLifecycle escrow;
    private final PersonDirectory people;
    private final BalanceProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    BalanceHoldService(
            BookingMoney money,
            CustomerQuotes quotes,
            PaymentAuthorizations payments,
            EscrowLifecycle escrow,
            PersonDirectory people,
            BalanceProperties props,
            TransactionTemplate tx,
            Clock clock) {
        this.money = money;
        this.quotes = quotes;
        this.payments = payments;
        this.escrow = escrow;
        this.people = people;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public int holdDue() {
        var due = Objects.requireNonNull(tx.execute(_ -> money.dueBalances(clock.instant(), BATCH)));
        var held = 0;
        for (var balance : due) {
            try {
                if (Boolean.TRUE.equals(tx.execute(_ -> hold(balance)))) {
                    held++;
                }
            } catch (RuntimeException e) {
                log.error("Balance of booking {} not processed; retried next run", balance.bookingId(), e);
            }
        }
        return held;
    }

    private boolean hold(BookingMoney.DueBalance b) {
        var now = clock.instant();
        var attempt = b.attempts() + 1;
        var facts = money.facts(b.customerId(), b.bookingId()).orElse(null);
        var quoteId = facts == null ? null : facts.quoteId();
        var deposit = quoteId == null
                ? null
                : quotes.acceptance(b.customerId(), quoteId)
                        .map(CustomerQuotes.Acceptance::paymentIntent)
                        .orElse(null);
        if (deposit == null) {
            money.balanceFailed(b.bookingId(), "no_saved_card", null, now);
            return false;
        }
        var result = payments.startOffSession(
                new PaymentAuthorizations.Request(
                        b.merchantId(),
                        BALANCE,
                        b.bookingId(),
                        b.customerId(),
                        b.cents(),
                        b.taxCents(),
                        "booking:" + b.bookingId(),
                        null),
                deposit,
                attempt);
        var intent = result.paymentIntent();
        if (result.authorized() && intent != null) {
            var customer = people.people(List.of(b.customerId())).get(b.customerId());
            var escrowId = escrow.hold(new EscrowLifecycle.Hold(
                    b.merchantId(),
                    EscrowKind.SERVICE,
                    BALANCE,
                    b.bookingId(),
                    b.cents(),
                    b.taxCents(),
                    b.customerId(),
                    customer == null ? "Customer" : customer.shortName(),
                    b.title() + " · balance",
                    null,
                    null,
                    null,
                    "search",
                    intent,
                    now));
            money.balanceHeld(b.bookingId(), escrowId, now);
            log.info("Booking {}: balance of {} + {} held ({})", b.ref(), b.cents(), b.taxCents(), intent);
            return true;
        }
        var next = now.plus(props.retryEvery());
        var retryAt = attempt < props.attempts() && next.isBefore(b.startsAt()) ? next : null;
        money.balanceFailed(b.bookingId(), Objects.requireNonNullElse(result.failure(), "declined"), retryAt, now);
        log.warn(
                "Booking {}: balance not held (attempt {}, {}); {}",
                b.ref(),
                attempt,
                result.failure(),
                retryAt == null ? "no more attempts" : "retry at " + retryAt);
        return false;
    }
}
