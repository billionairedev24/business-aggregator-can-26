package ca.northline.payments.application;

import ca.northline.payments.application.ReconcileStripe.Day;
import ca.northline.payments.application.ReconcileStripe.Item;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port of {@link ReconcileStripe}: the ledger's side and the stored days. */
public interface ReconciliationStore {

    /**
     * The ledger's {@code stripe_balance} postings in [from, to) by reference, with the Stripe object behind each
     * (the charge of an escrow's or a delivery fee's PaymentIntent, the refund, the card dispute, the payout).
     */
    List<Posting> postings(Instant from, Instant to);

    /** Payouts created in [from, to) that Stripe accepted (not failed or canceled), with their net amount. */
    List<StripePayout> payouts(Instant from, Instant to);

    Optional<Day> day(LocalDate day);

    List<Day> days(LocalDate from, LocalDate to);

    List<Item> items(LocalDate day);

    /** Replaces the day and its items. */
    void save(Day day, Instant from, Instant to, List<Item> items);

    void resolve(LocalDate day, String note, String userId, Instant at);

    /** Ledger entries in [from, to), by time: at, account, debit, credit, ref_type, ref_id. */
    List<List<String>> ledger(Instant from, Instant to);

    /**
     * @param refType {@code escrow} | {@code order_delivery} | {@code refund} | {@code dispute} | {@code payout}
     * @param stripeId the Stripe object, null when the ledger knows none
     * @param cents debits − credits
     */
    record Posting(String refType, String refId, @Nullable String stripeId, long cents) {}

    record StripePayout(String id, String stripePayout, long netCents) {}
}
