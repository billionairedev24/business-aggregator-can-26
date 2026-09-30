package ca.northline.payments.application;

import ca.northline.payments.domain.Payout;
import java.time.Instant;

/**
 * Outbound port: payouts and bank accounts on a merchant's Stripe connected account. Northline decides when money is
 * paid out (reserve, holds for open cases, the 24 h bank-change hold), so Stripe's own schedule on every connected
 * account is {@code manual} and each payout is created here. Account numbers pass through to Stripe and are never
 * stored; Northline keeps Stripe's reference and the last four digits. Linking a bank is {@link BankLinking}.
 */
public interface PayoutGateway {

    /** A payout Stripe accepted. */
    record Sent(String payoutId, Instant arrivesAt) {}

    /** Pays {@code amountCents} from the connected account's Stripe balance to its bank ({@code po_…}). */
    Sent payout(String connectedAccount, long amountCents, boolean instant, String externalRef, String idempotencyKey);

    /**
     * Instant payouts: Stripe bills Northline the instant payout fee, which the merchant pays (design 02, "1 % fee").
     * The payout sends {@code amount − fee}; this moves the fee from the connected account to the platform (an
     * account debit), so the connected account's balance keeps matching the merchant's ledger balance.
     */
    String recoverFee(String connectedAccount, long feeCents, String stripePayout, String idempotencyKey);

    /** A payout's current state at Stripe (the reconciler for payouts whose webhook never came). */
    Payout.State payoutState(String connectedAccount, String stripePayout);

    /**
     * Whether Stripe webhooks report this gateway's payouts ({@code payout.paid} / {@code failed}); the fake sends
     * none, so its payouts are reconciled as soon as they arrive.
     */
    boolean webhooksDeliver();

    /** Turns Stripe's automatic payouts off for the account: Northline's scheduled run creates every payout. */
    void useManualPayouts(String connectedAccount);

    /** Payouts go to this account from now on. */
    void makeDefault(String connectedAccount, String externalRef);
}
