package ca.northline.payments.application;

import ca.northline.payments.domain.Payout;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: payouts and bank accounts on a merchant's Stripe connected account. Northline decides when money is
 * paid out (reserve, holds for open cases, the 24 h bank-change hold), so Stripe's own schedule on every connected
 * account is {@code manual} and each payout is created here. Account numbers pass through to Stripe and are never
 * stored; Northline keeps Stripe's reference and the last four digits.
 */
public interface PayoutGateway {

    /** A payout Stripe accepted. */
    record Sent(String payoutId, Instant arrivesAt) {}

    /** How the Studio links a bank "instantly": Stripe Financial Connections, or the local fake. */
    record LinkSession(
            String mode,
            @Nullable String clientSecret,
            @Nullable String publishableKey) {}

    /** A bank account as Stripe knows it. */
    record BankAccount(
            String externalRef,
            String institutionName,
            @Nullable String institutionNumber,
            @Nullable String transitNumber,
            String last4) {}

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

    /** Turns Stripe's automatic payouts off for the account: Northline's scheduled run creates every payout. */
    void useManualPayouts(String connectedAccount);

    LinkSession startBankLink(String connectedAccount);

    /** The account the owner picked in Financial Connections ({@code fca_…}). */
    BankAccount linked(String connectedAccount, String linkedAccountRef);

    /** Typed institution / transit / account: tokenized at Stripe. */
    BankAccount manual(
            String connectedAccount, String institution, String transit, String accountNumber, String holderName);

    /** Payouts go to this account from now on. */
    void makeDefault(String connectedAccount, String externalRef);
}
