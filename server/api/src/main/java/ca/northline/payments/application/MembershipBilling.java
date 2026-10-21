package ca.northline.payments.application;

import ca.northline.payments.api.PlusBilling;
import java.time.Instant;
import java.util.List;

/**
 * Port (S-152; Northline's own since S-162 — {@code NorthlineMembershipBilling}, no Stripe Billing adapter): the Plus
 * billing work nobody asks for in the moment — the renewal job (charges due periods off-session, retries refused ones on
 * {@code PLUS_RETRY_DAYS}, lapses the rest), Stripe's PaymentIntent webhooks for Plus, the tax report, and the console's
 * refunds of a period. Stripe is called for PaymentIntents and refunds only.
 */
public interface MembershipBilling {

    /** Due periods, each in its own transaction (a concurrent run skips the ones it can't lock). @return periods changed */
    int chargeDue(Instant now);

    /** Paid periods whose tax Stripe Tax hasn't recorded yet. @return transactions recorded */
    int reportTax(int limit);

    /** A {@code payment_intent.*} webhook: false when the PaymentIntent isn't a Plus period's. */
    boolean paymentIntentChanged(String paymentIntent);

    /**
     * @param ended the refunded period was the current one: the membership ended at once
     */
    record Refunded(PlusBilling.Period period, String refundId, long totalCents, boolean ended) {}

    /** A paid period back to the card (amount + tax), audited; 409 {@code plus_not_refundable} otherwise. */
    Refunded refund(String periodId, String staffId, String role);

    /** The household's periods, every membership, newest first (the console). */
    List<PlusBilling.Period> periodsOf(String householdId);
}
